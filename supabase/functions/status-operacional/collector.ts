import {sql} from './db.ts';
import {pack,unpack} from './codec.ts';
import {buildSnapshot,normalize,reason} from '../_shared/report-contract.ts';
type Row=Record<string,any>;
const key=(r:Row)=>JSON.stringify([String(r.id),r.equipmentTag,reason(r.reasonName)]);
async function windowRows(days:number,endDays=0):Promise<Row[]> {
 const now=new Date();const local=new Intl.DateTimeFormat('en-CA',{timeZone:'America/Sao_Paulo',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(now);const p=Object.fromEntries(local.map(v=>[v.type,v.value]));
 const midnight=Date.UTC(+p.year,+p.month-1,+p.day,3);
 const url=new URL('https://dr-db.grm.valeglobal.net/api/v2/status-log-base-report');url.searchParams.set('token',Deno.env.get('EDGE_GRM_TOKEN')!);url.searchParams.set('begin',new Date(midnight-days*86400000).toISOString());url.searchParams.set('end',endDays?new Date(midnight-endDays*86400000).toISOString():now.toISOString());
 const response=await fetch(url,{redirect:'error',signal:AbortSignal.timeout(20000)});if(!response.ok)throw new Error('Source unavailable');
 const reader=response.body!.getReader();const chunks:Uint8Array[]=[];let size=0;
 while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>10000000){await reader.cancel();throw new Error('Source too large');}chunks.push(value);}
 const buf=new Uint8Array(size);let offset=0;for(const chunk of chunks){buf.set(chunk,offset);offset+=chunk.length;}
 const raw=JSON.parse(new TextDecoder().decode(buf));if(!Array.isArray(raw)||!raw.length||raw.length>50000)throw new Error('Invalid source');
 return raw.map((r:Row)=>{if(r.id==null||!r.equipmentTag||!r.reasonName)throw new Error('Invalid row');const n=normalize(r);if(n.start==null || (n.end!=null && n.end<n.start))throw new Error('Invalid date');return n;});
}
function compact(parts:Row[],active:Row[]):Row[] {
 const keys=new Set(active.map(key));const relevant=parts.filter(r=>keys.has(key(r)));
 const boundaries=new Set(relevant.map(r=>JSON.stringify([r.equipmentTag,r.startDate])));
 relevant.push(...parts.filter(r=>!keys.has(key(r))&&boundaries.has(JSON.stringify([r.equipmentTag,r.endDate]))));
 return [...new Map(relevant.map(r=>[JSON.stringify([key(r),r.startDate]),r])).values()].map(r=>({id:r.id,equipmentTag:r.equipmentTag,reasonName:r.reasonName,startDate:r.startDate,endDate:r.endDate,operator:r.operator,location:r.location,statusType:r.statusType,equipmentType:r.equipmentType,kpiCategory:r.kpiCategory}));
}
export async function collect():Promise<Record<string,any>> {
 return await sql.begin(async tx=>{
  const lock=await tx`select pg_try_advisory_xact_lock(7150231103) acquired`;if(!lock[0].acquired)return {skipped:true};
  const [state]=await tx`select attempted_at,history_gzip,history_cursor,history_retry_at from operational.edge_state where singleton for update`;
  if(state.attempted_at && Date.now()-new Date(state.attempted_at).getTime()<55000)return {skipped:true};
  await tx`update operational.edge_state set attempted_at=now(),failed=true where singleton`;
  try {
   const current=await windowRows(1);const base=buildSnapshot(current,new Date().toISOString(),true);const active=base.equipment.filter((e:Row)=>e.current).map((e:Row)=>e.current);
   const boundaries=new Map<string,number>(active.map((r:Row)=>[key(r),r.startDate]));
   let history=(state.history_gzip?await unpack(state.history_gzip):[]).map((r:Row)=>normalize(r,true));
   // Older slices cannot replace the live source cohort or extend past its start.
   history=history.map((r:Row)=>boundaries.has(key(r))&&r.startDate<boundaries.get(key(r))!?{...r,endDate:Math.min(r.endDate??Infinity,boundaries.get(key(r))!),end:Math.min(r.endDate??Infinity,boundaries.get(key(r))!)}:r);
   let parts=[...history.filter((r:Row)=>!boundaries.has(key(r))||r.startDate<boundaries.get(key(r))!),...current];
   let snapshot=buildSnapshot(parts,new Date().toISOString(),true);
   const unresolved=snapshot.equipment.some((e:Row)=>e.current&&!e.current.startConfirmed);
   let cursor=state.history_cursor??1;let retryAt=state.history_retry_at;
   if(unresolved && (!retryAt || new Date(retryAt).getTime()<=Date.now())) {
    try {parts.push(...await windowRows(cursor+1,cursor));cursor++;if(cursor>=91){cursor=1;retryAt=new Date(Date.now()+3600000);}} catch {retryAt=new Date(Date.now()+3600000);}
    snapshot=buildSnapshot(parts,new Date().toISOString(),true);
   } else if(!unresolved){cursor=1;retryAt=null;}
   const kept=compact(parts,active);delete snapshot.records;
   const payloadGzip=await pack(snapshot),historyGzip=await pack(kept);
   await tx`update operational.edge_state set payload_gzip=${payloadGzip},history_gzip=${historyGzip},history_cursor=${cursor},history_retry_at=${retryAt},failed=false,updated_at=now() where singleton`;
   return {updatedAt:snapshot.updatedAt,equipment:snapshot.equipment.length,historyCursor:cursor,unconfirmed:snapshot.equipment.filter((e:Row)=>e.current&&!e.current.startConfirmed).length};
  }catch{return {sourceAvailable:false};}
 });
}
export async function readSnapshot():Promise<Row|null> {
 const [state]=await sql`select payload_gzip,attempted_at,failed from operational.edge_state where singleton`;
 if(!state?.payload_gzip)return null;const payload=await unpack(state.payload_gzip);const age=Math.max(0,Math.floor((Date.now()-Date.parse(payload.updatedAt))/1000));const stale=state.failed||age>120;
 return {...payload,stale,ageSeconds:age,serverTime:new Date().toISOString(),lastAttemptAt:state.attempted_at,sourceAvailable:!stale,persistenceAvailable:true,refreshIntervalSeconds:60};
}
