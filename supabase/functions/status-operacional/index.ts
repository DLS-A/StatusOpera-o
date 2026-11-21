import {sql,digest,HttpError} from './db.ts';
import {collect,readSnapshot} from './collector.ts';
import {pushRoute,notify} from './push.ts';
import {checkFCM} from './fcm.ts';
async function jsonBody(req:Request){const text=await req.text();if(text.length>32768)throw new HttpError(413);try{return text?JSON.parse(text):{};}catch{throw new HttpError(422);}}
Deno.serve(async (req:Request)=>{
 try{
  const url=new URL(req.url);const marker='/status-operacional';const pos=url.pathname.indexOf(marker);const path=pos>=0?url.pathname.slice(pos+marker.length):url.pathname;
  if(path.startsWith('/internal/')){
   const h=req.headers.get('authorization')??'';if(!h.startsWith('Bearer ')||await digest(h.slice(7))!==Deno.env.get('EDGE_JOB_SECRET_HASH'))throw new HttpError(401);
   if(req.method!=='POST')throw new HttpError(405);
   if(path==='/internal/fcm-check')return Response.json(await checkFCM());
   if(path==='/internal/collect')return Response.json(await collect());
   if(path==='/internal/notify'){const s=await readSnapshot();return Response.json(s?await notify(s):{skipped:true});}
   throw new HttpError(404);
  }
  if(path==='/v1/snapshot'||path==='/v1/readiness'){
   if(req.method!=='GET')throw new HttpError(405);const s=await readSnapshot();if(!s)throw new HttpError(503);
   if(path==='/v1/readiness'){if(s.stale)throw new HttpError(503);return Response.json({status:'ready',updatedAt:s.updatedAt});}
   const headers=new Headers({'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','Vary':'Accept-Encoding'});
   const bytes=new TextEncoder().encode(JSON.stringify(s));
   if((req.headers.get('accept-encoding')??'').includes('gzip')){headers.set('Content-Encoding','gzip');return new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip')),{headers});}
   return new Response(bytes,{headers});
  }
  if(path.startsWith('/v1/push/'))return Response.json(await pushRoute(req,path,await jsonBody(req)));
  if(path.startsWith('/v1/devices'))throw new HttpError(401);
  throw new HttpError(404);
 }catch(e){const status=e instanceof HttpError?e.status:503;return Response.json({detail:status===503?'Serviço temporariamente indisponível.':'Solicitação recusada.'},{status});}
});
