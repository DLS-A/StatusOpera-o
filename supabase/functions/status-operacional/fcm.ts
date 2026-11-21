import {HttpError} from './db.ts';
let cached:{token:string,until:number}|null=null;
const base64url=(data:Uint8Array)=>btoa(String.fromCharCode(...data)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const encode=(o:unknown)=>base64url(new TextEncoder().encode(JSON.stringify(o)));
async function accessToken():Promise<string> {
 if(cached && cached.until>Date.now())return cached.token;
 const sa=JSON.parse(Deno.env.get('EDGE_FCM_SERVICE_ACCOUNT')!);const now=Math.floor(Date.now()/1000);
 const body=encode({alg:'RS256',typ:'JWT'})+'.'+encode({iss:sa.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
 const der=Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----/g,'').replace(/\s/g,'')),c=>c.charCodeAt(0));
 const key=await crypto.subtle.importKey('pkcs8',der,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
 const signature=new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(body)));
 const r=await fetch('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:body+'.'+base64url(signature)}),signal:AbortSignal.timeout(10000)});
 if(!r.ok)throw new HttpError(503,'Notification authentication unavailable');const d=await r.json();cached={token:d.access_token,until:Date.now()+3000000};return cached.token;
}
export async function sendFCM(token:string,data:Record<string,string>,ttl='300s',validateOnly=false):Promise<void> {
 const sa=JSON.parse(Deno.env.get('EDGE_FCM_SERVICE_ACCOUNT')!);
 const r=await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,{method:'POST',headers:{Authorization:'Bearer '+await accessToken(),'Content-Type':'application/json'},body:JSON.stringify({validate_only:validateOnly,message:{token,data,android:{priority:'HIGH',ttl}}}),signal:AbortSignal.timeout(10000)});
 if(!r.ok){const d=await r.json().catch(()=>({}));const details=d.error?.details??[];if(details.some((v:any)=>['UNREGISTERED','SENDER_ID_MISMATCH'].includes(v.errorCode)))throw new HttpError(410,'Invalid push token');throw new HttpError(503,'Push delivery unavailable');}
}
// Authenticated internal diagnostic: validate-only, never delivers to a device.
export async function checkFCM():Promise<Record<string,unknown>> {
 const sa=JSON.parse(Deno.env.get('EDGE_FCM_SERVICE_ACCOUNT')!);const token=await accessToken();
 const r=await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({validate_only:true,message:{token:'not-a-real-fcm-token',data:{kind:'enrollment'},android:{priority:'HIGH',ttl:'300s'}}}),signal:AbortSignal.timeout(10000)});
 return {oauthAuthenticated:true,providerStatus:r.status,deliveryTested:false};
}
