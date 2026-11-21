import {HttpError} from './errors.ts';
type Row=Record<string,any>;
const canonical=(s:string)=>s.toUpperCase().normalize('NFD').replace(/\p{M}/gu,'').trim().replace(/\s+/g,' ');
export function planAlerts(snapshot:Row,device:Row,now=Date.now()):[Array<[string,string]>,string[],string[]] {
 if(snapshot.stale||snapshot.sourceAvailable===false||snapshot.persistenceAvailable===false)return [[],device.observed_maintenance??[],[]];
 const p=device.preferences??{};const canonMap=(o:Row)=>Object.fromEntries(Object.entries(o).map(([k,v])=>[canonical(k),v]));
 const limits=canonMap(p.limitsMinutesByStatus??{'PAUSA ERGONOMICA':15}),enabled=canonMap(p.alertsEnabledByStatus??{});const events:Array<[string,string]>=[],active:string[]=[];
 for(const e of snapshot.equipment){const r=e.current;if(!r)continue;const s=canonical(r.reasonName);if(p.visualEnabled===false||!(enabled[s]??(s==='PAUSA ERGONOMICA')))continue;active.push(r.id);let elapsed=Math.max(0,r.durationSeconds??0);if(r.durationMeasuredAtEpochMillis!=null)elapsed+=Math.max(0,(now-r.durationMeasuredAtEpochMillis)/1000);if(limits[s]&&elapsed>limits[s]*60)events.push([`limit:${r.id}:${limits[s]*60}`,r.id]);}
 const maint=new Map<string,Row>(snapshot.maintenanceAlerts.map((r:Row)=>[r.id,r]));
 if(p.visualEnabled!==false){for(const [id,r] of maint){if(enabled[canonical(r.reasonName)]!==false)active.push(id);}if(device.observed_maintenance!=null)for(const [id,r] of maint){if(!device.observed_maintenance.includes(id)&&enabled[canonical(r.reasonName)]!==false)events.push(['maintenance:'+id,id]);}}
 return [events,[...maint.keys()],active];
}
export function preferences(value:unknown):Row {
 const p=(value??{}) as Row;if(typeof p!=='object'||Array.isArray(p))throw new HttpError(422);
 const valid=new Set(['limitsMinutesByStatus','alertsEnabledByStatus','soundEnabled','visualEnabled','repeatMinutes']);if(Object.keys(p).some(k=>!valid.has(k)))throw new HttpError(422);
 const out:Row={limitsMinutesByStatus:{'PAUSA ERGONOMICA':15},alertsEnabledByStatus:{},soundEnabled:true,visualEnabled:true,repeatMinutes:5,...p};
 for(const name of ['limitsMinutesByStatus','alertsEnabledByStatus']){const m=out[name];if(!m||typeof m!=='object'||Array.isArray(m)||Object.keys(m).length>100)throw new HttpError(422);for(const [k,v] of Object.entries(m)){if(!k.trim()||k.length>150)throw new HttpError(422);if(name==='limitsMinutesByStatus'? !Number.isInteger(v)||Number(v)<1||Number(v)>1440 : typeof v!=='boolean')throw new HttpError(422);}}
 if(typeof out.soundEnabled!=='boolean'||typeof out.visualEnabled!=='boolean'||!Number.isInteger(out.repeatMinutes)||out.repeatMinutes<1||out.repeatMinutes>1440)throw new HttpError(422);return out;
}
