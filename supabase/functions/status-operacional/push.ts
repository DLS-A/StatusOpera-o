import {sql,digest,randomSecret,HttpError} from './db.ts';
import {sendFCM} from './fcm.ts';
import {planAlerts,preferences} from './push-rules.ts';
type Row=Record<string,any>;
const uuid=(v:string)=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
async function credential(req:Request){const v=req.headers.get('authorization')??'';if(!/^Bearer [A-Za-z0-9_-]{40,100}$/.test(v))throw new HttpError(401);return digest(v.slice(7));}
function owner(r:Row|undefined,hash:string){if(!r||r.secret_hash!==hash)throw new HttpError(403);}
export async function pushRoute(req:Request,path:string,body:Row):Promise<Row> {
 const hash=await credential(req);
 if(path==='/v1/push/enroll'&&req.method==='POST'){
  if(Object.keys(body).some(k=>!['deviceId','token','preferences'].includes(k))||!uuid(body.deviceId??'')||!(/^[A-Za-z0-9_:-]{20,4096}$/.test(body.token??'')))throw new HttpError(422);
  const prefs=preferences(body.preferences),challenge=randomSecret(),peer=await digest(req.headers.get('x-forwarded-for')?.split(',')[0].trim()??'unknown');
  const bucket=Math.floor(Date.now()/3600000);
  const rates=await sql`insert into operational.public_push_rates(peer_hash,bucket) values(${peer},${bucket}) on conflict(peer_hash,bucket) do update set attempts=operational.public_push_rates.attempts+1 returning attempts`;
  if(rates[0].attempts>30)throw new HttpError(429);
  const result=await sql.begin(async tx=>{
   await tx`select pg_advisory_xact_lock(7150231106)`;
   await tx`delete from operational.public_push_rates where bucket<${bucket-2}`;
   await tx`delete from operational.public_push_devices where token is null and challenge_expires<now()`;
   const [row]=await tx`select * from operational.public_push_devices where id=${body.deviceId} for update`;
   if(row){owner(row,hash);if(row.token===body.token){await tx`update operational.public_push_devices set preferences=${tx.json(prefs)},enabled=true,last_seen_at=now() where id=${body.deviceId}`;return {registered:true};}}
   const owners=await tx`select id from operational.public_push_devices where (token=${body.token} or pending_token=${body.token}) and id<>${body.deviceId}`;if(owners.length)throw new HttpError(403);
   if(!row){const [count]=await tx`select count(*) n from operational.public_push_devices`;if(Number(count.n)>=5000)throw new HttpError(429);}
   const ch=await digest(challenge);
   await tx`insert into operational.public_push_devices(id,secret_hash,pending_token,challenge_hash,challenge_expires,preferences) values(${body.deviceId},${hash},${body.token},${ch},now()+interval '10 minutes',${tx.json(prefs)}) on conflict(id) do update set pending_token=excluded.pending_token,challenge_hash=excluded.challenge_hash,challenge_expires=excluded.challenge_expires,preferences=excluded.preferences,last_seen_at=now()`;
   return {registered:false,confirmationPending:true};
  });
  if(!result.registered)await sendFCM(body.token,{kind:'enrollment',device_id:body.deviceId,challenge},'600s');return result;
 }
 const match=path.match(/^\/v1\/push\/([^/]+)(\/confirm)?$/);if(!match||!uuid(match[1]))throw new HttpError(404);const id=match[1];
 if(match[2]&&req.method==='PUT'){
  if(Object.keys(body).length!==1||!(/^[A-Za-z0-9_-]{40,100}$/.test(body.challenge??'')))throw new HttpError(422);
  return sql.begin(async tx=>{const [r]=await tx`select * from operational.public_push_devices where id=${id} for update`;owner(r,hash);if(!r.challenge_hash||!r.challenge_expires||new Date(r.challenge_expires).getTime()<=Date.now()||r.challenge_hash!==await digest(body.challenge))throw new HttpError(403);await tx`update operational.public_push_devices set token=pending_token,pending_token=null,challenge_hash=null,challenge_expires=null,enabled=true,last_seen_at=now() where id=${id}`;return {registered:true};});
 }
 if(!match[2]&&req.method==='DELETE')return sql.begin(async tx=>{const [r]=await tx`select * from operational.public_push_devices where id=${id} for update`;owner(r,hash);await tx`update operational.public_push_devices set enabled=false where id=${id}`;await tx`update operational.public_push_outbox set status='cancelled' where device_id=${id} and status='pending'`;return {removed:true};});
 throw new HttpError(405);
}
export async function notify(snapshot:Row):Promise<Row> {
 if(snapshot.stale)return {skipped:true};
 await sql.begin(async tx=>{
  const [lock]=await tx`select pg_try_advisory_xact_lock(7150231105) acquired`;if(!lock.acquired)return;
  const devices=await tx`select * from operational.public_push_devices where enabled and token is not null and last_seen_at>now()-interval '30 days'`;
  for(const d of devices){const [events,observed,active]=planAlerts(snapshot,d);const interval=Math.max(1,Math.min(1440,d.preferences.repeatMinutes??5))*60,slot=Math.floor(Date.now()/1000/interval);const keys=events.map(([k,r])=>[k.startsWith('maintenance:')?k:k+':repeat:'+slot,r]);
   await tx`update operational.public_push_devices set observed_maintenance=${tx.json(observed)} where id=${d.id}`;
   for(const [k,r] of keys)await tx`insert into operational.public_push_outbox(id,device_id,event_key,record_id) values(${crypto.randomUUID()},${d.id},${k},${r}) on conflict(device_id,event_key) do nothing`;
   await tx`update operational.public_push_outbox set status='cancelled' where device_id=${d.id} and status='pending' and not(event_key=any(${keys.map(v=>v[0])}::text[])) and not(event_key like 'maintenance:%' and record_id=any(${active}::text[]))`;
  }
  await tx`delete from operational.public_push_outbox where created_at<now()-interval '30 days'`;
 });
 let sent=0;const deadline=Date.now()+40000;
 for(let i=0;i<20&&Date.now()<deadline;i++){
  const found=await sql.begin(async tx=>{
   const [event]=await tx`select * from operational.public_push_outbox where status='pending' and next_attempt_at<=now() order by created_at limit 1 for update skip locked`;if(!event)return false;
   const [d]=await tx`select * from operational.public_push_devices where id=${event.device_id} and enabled and last_seen_at>now()-interval '30 days' for update`;
   if(!d||d.preferences.visualEnabled===false){await tx`update operational.public_push_outbox set status='cancelled' where id=${event.id}`;return true;}
   try{const original=event.event_key.split(':repeat:')[0],local=original.startsWith('maintenance:')?'maintenance:'+event.record_id:event.record_id;
    await sendFCM(d.token,{kind:'operational',event_id:await digest(original),alert_id:await digest(local),device_tag:await digest(d.id),sound:String(d.preferences.soundEnabled??true),repeat:String(!original.startsWith('maintenance:'))});
    await tx`update operational.public_push_outbox set status='sent',sent_at=now() where id=${event.id}`;sent++;
   }catch(e){const invalid=e instanceof HttpError&&e.status===410,attempts=event.attempts+1;if(invalid)await tx`update operational.public_push_devices set enabled=false where id=${d.id}`;await tx`update operational.public_push_outbox set status=${invalid||attempts>=5?'cancelled':'pending'},attempts=${attempts},next_attempt_at=now()+${Math.min(900,30*2**attempts)}*interval '1 second' where id=${event.id}`;}
   return true;
  });if(!found)break;
 }
 return {sent};
}
