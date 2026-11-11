"""Signed JWT + simulated providers/GRM/database; no external calls or credentials."""
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
import threading
import unittest
from unittest.mock import Mock, patch
from uuid import uuid4

from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials
import jwt
import requests

from backend.app import auth
from backend.app.grm_client import GrmClient, GrmUnavailable
from backend.app.settings import Settings, https_url
from backend.app.snapshot_service import SnapshotService, build_snapshot
from backend.app.notifications import FirebaseNotifier, plan_alerts, digest

ISSUER='https://provider.example/auth/v1'

class AuthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)

    def setUp(self):
        now=datetime.now(timezone.utc)
        self.claims={'iss':ISSUER,'aud':'authenticated','client_id':'mobile-client','role':'authenticated',
                     'sub':str(uuid4()),'session_id':str(uuid4()),'iat':now,'exp':now+timedelta(minutes=10)}
        self.store=Mock(); self.store.session_active.return_value=True
        self.user={'id':self.claims['sub'],'email':'Allowed@Example.com','email_confirmed_at':now.isoformat()}
        jwks=Mock(); jwks.get_signing_key_from_jwt.return_value=SimpleNamespace(key=self.key.public_key())
        self.config=patch.object(auth,'_oidc_config',return_value=(ISSUER,'authenticated','mobile-client',jwks))
        self.emails=patch.object(auth,'allowed_emails',return_value={'allowed@example.com'})
        self.provider=patch.object(auth,'_verified_user',side_effect=lambda *args:self.user)
        for p in (self.config,self.emails,self.provider):p.start();self.addCleanup(p.stop)

    def authorize(self,claims=None,algorithm='RS256',key=None):
        token=jwt.encode(claims or self.claims,key or self.key,algorithm=algorithm)
        return auth.require_user(HTTPAuthorizationCredentials(scheme='Bearer',credentials=token),self.store)

    def rejection(self,code,operation):
        with self.assertRaises(HTTPException) as ctx:operation()
        self.assertEqual(ctx.exception.status_code,code)

    def test_verified_email_not_required_on_access_claim(self):
        self.assertNotIn('email_verified',self.authorize())
        self.assertEqual(self.authorize()['email'],'allowed@example.com')
    def test_missing_bearer(self):self.rejection(401,lambda:auth.require_user(None,self.store))
    def test_wrong_signature(self):self.rejection(401,lambda:self.authorize(key=rsa.generate_private_key(public_exponent=65537,key_size=2048)))
    def test_expired_wrong_issuer_audience_and_missing_session(self):
        for name,value in [('exp',datetime.now(timezone.utc)-timedelta(seconds=5)),('iss','https://other.example'),('aud','other'),('session_id','not-uuid')]:
            with self.subTest(name=name):self.rejection(401,lambda:self.authorize({**self.claims,name:value}))
        claims=dict(self.claims);del claims['session_id']
        self.rejection(401,lambda:self.authorize(claims))
    def test_wrong_client_and_role(self):
        for name in ('client_id','role'):
            self.rejection(403,lambda:self.authorize({**self.claims,name:'other'}))
    def test_unconfirmed_unlisted_and_mismatched_user(self):
        for name,value in [('email_confirmed_at',None),('email','outsider@example.com'),('id',str(uuid4()))]:
            old=self.user[name];self.user[name]=value
            self.rejection(403,self.authorize);self.user[name]=old
    def test_revoked_session(self):
        self.store.session_active.return_value=False;self.rejection(401,self.authorize)
    def test_provider_banned_user_denied(self):
        self.user['banned_until']=(datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        self.rejection(403,self.authorize)
    def test_session_database_failure_fails_closed(self):
        self.store.session_active.side_effect=RuntimeError();self.rejection(503,self.authorize)
    def test_provider_network_failure_and_no_redirect(self):
        self.provider.stop()
        with patch.object(auth,'public_key',return_value='sb_publishable_mock'),patch.object(auth.requests,'get',side_effect=requests.Timeout()) as get:
            self.rejection(503,lambda:auth._verified_user('mock',ISSUER));self.assertFalse(get.call_args.kwargs['allow_redirects'])
    def test_service_role_and_secret_key_rejected(self):
        for key in ['sb_secret_mock',jwt.encode({'role':'service_role'},'nonsecret-test-key'*3,algorithm='HS256')]:
            with patch.dict('os.environ',{'SUPABASE_PUBLISHABLE_KEY':key}):
                with self.assertRaises(ValueError):auth.public_key()
    def test_http_credentials_query_and_fragment_urls_rejected(self):
        for url in ['http://host','https://user:pass@host','https://host?token=test','https://host/#fragment']:
            with self.assertRaises(ValueError):https_url(url)

class GrmTests(unittest.TestCase):
    def setUp(self):
        self.settings=Settings('https://example.invalid/grm','mock-secret','unused')
        self.session=Mock();self.sleeper=Mock();self.client=GrmClient(self.settings,self.session,self.sleeper)
    def response(self,status=200,body=b'[]'):
        r=Mock();r.status_code=status;r.iter_content.return_value=[body];r.__enter__=Mock(return_value=r);r.__exit__=Mock(return_value=False);return r
    def test_get_only_original_query_and_redirect_block(self):
        self.session.get.return_value=self.response()
        self.assertEqual(self.client.fetch(),[])
        args=self.session.get.call_args.kwargs
        self.assertEqual(set(args['params']),{'token','begin','end'});self.assertFalse(args['allow_redirects']);self.assertEqual(args['timeout'],(5,15))
        self.assertRegex(args['params']['begin'],r'T03:00:00\.000Z$')
        self.session.post.assert_not_called()
    def test_transient_retries_bounded(self):
        self.session.get.side_effect=[self.response(503),requests.Timeout(),self.response()]
        self.assertEqual(self.client.fetch(),[]);self.assertEqual(self.session.get.call_count,3);self.assertEqual(self.sleeper.call_count,2)
    def test_auth_and_redirect_not_retried(self):
        for status in (401,403,302):
            self.session.get.reset_mock();self.session.get.return_value=self.response(status)
            with self.assertRaises(GrmUnavailable):self.client.fetch()
            self.assertEqual(self.session.get.call_count,1)
    def test_empty_invalid_json_invalid_records_and_size_limit(self):
        for body in (b'',b'<html>',b'{}',b'[{"x":1}]',b'[{"id":"invalid"}]',b'x'*(10*1024*1024+1)):
            self.session.get.return_value=self.response(body=body)
            with self.assertRaises(GrmUnavailable):self.client.fetch()
    def test_connection_failure_exhausts_without_token_error_text(self):
        self.session.get.side_effect=requests.ConnectionError('https://host?token=mock-secret')
        with self.assertRaises(GrmUnavailable) as ctx:self.client.fetch()
        self.assertNotIn('mock-secret',str(ctx.exception));self.assertEqual(self.session.get.call_count,3)

class SnapshotTests(unittest.TestCase):
    def test_poll_cadence_uses_attempt_not_full_interval_after_work(self):
        import asyncio
        from unittest.mock import AsyncMock
        self.service.restore()
        self.now += timedelta(seconds=18)
        with patch.object(self.service,'refresh'), patch('backend.app.snapshot_service.asyncio.sleep',
                new=AsyncMock(side_effect=asyncio.CancelledError)) as sleep:
            with self.assertRaises(asyncio.CancelledError):asyncio.run(self.service.run())
        sleep.assert_awaited_once_with(43.0)

    def setUp(self):
        self.now=datetime.now(timezone.utc)
        self.settings=Settings('https://example.invalid','mock','unused')
        self.payload={'updatedAt':self.now.isoformat(),'equipment':[],'pauses':[],'maintenance':[],'maintenanceAlerts':[],'records':[]}
        self.store=Mock();self.client=Mock();self.notifier=Mock()
        self.store.latest.return_value=(self.payload,{'attempted_at':self.now,'failed':False})
        self.service=SnapshotService(self.settings,self.store,self.client,self.notifier,clock=lambda:self.now)
    def test_restore_deep_copy_read_no_upstream_and_no_private_records(self):
        self.service.restore();result=self.service.read();self.assertFalse(result['stale']);self.assertNotIn('records',result)
        result['equipment'].append({});self.assertEqual(self.service.read()['equipment'],[]);self.client.fetch.assert_not_called()
    def test_age_and_database_failure_suppress_alerts_preserve_last_valid(self):
        self.service.restore();self.now+=timedelta(seconds=121)
        self.assertTrue(self.service.read()['stale'])
        self.store.poll.side_effect=RuntimeError();self.service.refresh()
        self.assertFalse(self.service.read()['persistenceAvailable']);self.notifier.process.assert_not_called()
    def test_recovery_clears_stale(self):
        self.service.restore();self.store.poll.return_value=(self.payload,{'attempted_at':self.now,'failed':True});self.service.refresh()
        self.assertTrue(self.service.read()['stale'])
        self.store.poll.return_value=(self.payload,{'attempted_at':self.now,'failed':False});self.service.refresh()
        self.assertFalse(self.service.read()['stale']);self.notifier.process.assert_called_once()
    def test_unconfigured_kpi_not_invented(self):
        from functions import normalizar_apontamento
        r=normalizar_apontamento({'id':'mock','equipmentTag':'TEST','reasonName':'ANY','startDate':self.now.isoformat(),'kpiCategory':'SOME'})
        self.assertEqual(build_snapshot([r],self.settings)['equipment'][0]['operationalState'],'unknown')
        settings=Settings('https://example.invalid','mock','unused',operating_categories=frozenset({'SOME'}))
        self.assertEqual(build_snapshot([r],settings)['equipment'][0]['operationalState'],'unknown')
        r['statusType']='Operating'
        self.assertEqual(build_snapshot([r],settings)['equipment'][0]['operationalState'],'operating')
        r['statusType']='Operational Downtime'
        self.assertEqual(build_snapshot([r],settings)['equipment'][0]['operationalState'],'stopped')
    def test_empty_snapshot_can_be_confirmed(self):
        self.assertEqual(build_snapshot([])['equipment'],[])

class PlannerTests(unittest.TestCase):
    def test_fcm_payload_contains_only_generic_data_and_no_notification_body(self):
        from firebase_admin import messaging
        notifier=FirebaseNotifier(Mock());notifier._app=Mock()
        device={'token':'synthetic-fcm-token','session_id':uuid4(),'preferences':{'soundEnabled':False}}
        event={'event_key':'limit:private-record:900','record_id':'private-record'}
        with patch.object(messaging,'send') as send:
            notifier._send(device,event)
        message=send.call_args.args[0]
        self.assertIsNone(message.notification)
        self.assertEqual(set(message.data),{'event_id','alert_id','session_tag','sound'})
        self.assertNotIn('private-record',str(message.data));self.assertEqual(message.data['sound'],'false')
        self.assertEqual(message.android.priority,'high')

    def test_baseline_new_event_and_generic_hash(self):
        snapshot={'equipment':[],'maintenanceAlerts':[{'id':'new','reasonName':'EXEC MANUT CORRETIVA'}]}
        events,observed,active=plan_alerts(snapshot,{'observed_maintenance':None})
        self.assertEqual(events,[]);self.assertIn('new',active)
        events,_,_=plan_alerts(snapshot,{'observed_maintenance':[]})
        self.assertEqual(events,[('maintenance:new','new')]);self.assertEqual(len(digest(events[0][0])),64)
    def test_visual_disabled_and_explicit_status_disabled(self):
        snapshot={'equipment':[],'maintenanceAlerts':[{'id':'new','reasonName':'EXEC MANUT CORRETIVA'}]}
        for preferences in ({'visualEnabled':False},{'alertsEnabledByStatus':{'EXEC MANUT CORRETIVA':False}}):
            events,_,active=plan_alerts(snapshot,{'observed_maintenance':[],'preferences':preferences})
            self.assertEqual(events,[]);self.assertEqual(active,[])
    def test_limit_matches_android_event_key_and_duration_fallback(self):
        snapshot={'equipment':[{'current':{'id':'pause','reasonName':'PAUSA ERGONOMICA','startEpochMillis':None,'durationSeconds':901}}],'maintenanceAlerts':[]}
        self.assertEqual(plan_alerts(snapshot,{})[0],[('limit:pause:900','pause')])
        snapshot['equipment'][0]['current']['durationSeconds']=900
        self.assertEqual(plan_alerts(snapshot,{})[0],[])

if __name__=='__main__':unittest.main()