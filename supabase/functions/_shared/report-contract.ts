// Deployed Supabase contract. Exact pointing dates verified against the Python GRM adapter.
type Row = Record<string, any>;
export const reason = (v: unknown) => String(v ?? '').toUpperCase().normalize('NFD').replace(/\p{M}/gu, '').trim().replace(/\s+/g, ' ').replace(/\s*-\s*/g, '-');
const maintenance = new Set(['AG MANUTENCAO','EXEC MANUT CORRETIVA','EXEC MANUT PREVENTIVA','EXEC MANUT PREVENTIVA-250 HRS']);
const saoPaulo = new Intl.DateTimeFormat('en-GB', {timeZone:'America/Sao_Paulo',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
// Resolve wall time through IANA rules, including historical DST, not a fixed +3h.
function sourceDate(value: any): any {
  if (value == null || value === 0) return value;
  if (typeof value !== 'number' && !/^[+-]?\d+$/.test(String(value))) return value;
  const wall = Number(value);
  if (!Number.isFinite(wall)) throw new Error('Invalid source date');
  let instant = wall;
  for (let i=0;i<3;i++) {
    const parts = Object.fromEntries(saoPaulo.formatToParts(new Date(instant)).map(p=>[p.type,p.value]));
    const local = Date.UTC(+parts.year,+parts.month-1,+parts.day,+parts.hour,+parts.minute,+parts.second) + ((instant % 1000 + 1000) % 1000);
    const next = instant + wall - local;
    if (next === instant) break;
    instant = next;
  }
  return instant;
}
function epoch(v: any): number|null {
  if (v == null || v === 0 || v === '') return null;
  const n = typeof v === 'number' || /^[+-]?\d+$/.test(String(v)) ? Number(v) : Date.parse(v);
  return Number.isFinite(n) ? n : null;
}
export function normalize(raw: Row, corrected = false): Row {
  const startDate = corrected ? raw.startDate : sourceDate(raw.startDate), endDate = corrected ? raw.endDate : sourceDate(raw.endDate);
  const operator = String(raw.operator ?? '').trim();
  return {...raw,startDate,endDate,start:epoch(startDate),end:epoch(endDate),aberto:endDate == null || endDate === 0,
    operator: new Set(['','-','NULL','NONE','NAO INFORMADO','SEM OPERADOR']).has(reason(operator)) ? 'Apontado via Web' : operator,
    statusType:String(raw.statusType??'').trim(), location:String(raw.location??'').trim()};
}
const key = (r:Row) => JSON.stringify([String(r.id),r.equipmentTag,reason(r.reasonName)]);
function publicRecord(r: Row): Row {
  return {id:String(r.id),equipmentTag:String(r.equipmentTag??''),reasonName:String(r.reasonName??''),startDate:r.startDate,
    startEpochMillis:r.start,startConfirmed:r.confirmed??false,statusType:String(r.statusType??''),endDate:r.endDate??null,
    durationMeasuredAtEpochMillis:r.end,durationSeconds:r.start==null?0:Math.max(0,Math.floor(((r.end??Date.now())-r.start)/1000)),
    operator:String(r.operator??''),location:String(r.location??''),equipmentType:String(r.equipmentType??''),kpiCategory:String(r.kpiCategory??'')};
}
export function buildSnapshot(raw: Row[], updatedAt = new Date().toISOString(), corrected = false): Row {
  const rows = raw.map(r=>normalize(r,corrected)), slices = new Map<string,Row[]>(), predecessors = new Map<string,Set<string>>();
  for (const r of rows) {
    const k=key(r); if(!slices.has(k)) slices.set(k,[]); slices.get(k)!.push(r);
    if(r.end!=null) {const p=JSON.stringify([r.equipmentTag,r.end]); if(!predecessors.has(p)) predecessors.set(p,new Set()); predecessors.get(p)!.add(String(r.id));}
  }
  const explicit = rows.filter(r=>r.aberto);
  const ends = rows.filter(r=>r.end!=null).map(r=>r.end);
  const cutoff = ends.length ? ends.reduce((a,b)=>Math.max(a,b)) : null;
  const capped = rows.filter(r=>cutoff!=null && r.end===cutoff);
  const candidates = explicit.length ? explicit : new Set(capped.map(r=>r.equipmentTag)).size>=2 ? capped : [];
  const current = new Map<string,Row>();
  for (const row of candidates) {
    const old=current.get(row.equipmentTag);
    if(!old || (row.start??0)>(old.start??0) || (row.start===old.start && String(row.id)>String(old.id))) current.set(row.equipmentTag,row);
  }
  for (const [tag,row] of current) {
    const r={...row}; let begin=r.start;
    if(begin!=null) {
      const previous=slices.get(key(r))!.filter(p=>p.start!=null && p.start<begin).sort((a,b)=>b.start-a.start);
      for(const p of previous) {if(p.end==null || p.end<begin) break; begin=p.start;r.start=begin;r.startDate=p.startDate;}
      r.confirmed=[...(predecessors.get(JSON.stringify([tag,begin]))??[])].some(id=>id!==String(r.id));
    }
    current.set(tag,r);
  }
  const state=(r?:Row) => {
    const s=reason(r?.statusType);
    if(['OPERATING','OPERACAO'].includes(s)) return 'operating';
    if(['OPERATIONAL DOWNTIME','PARADA OPERACIONAL'].includes(s)) return 'stopped';
    if(['PLANNED MAINTENANCE','UNPLANNED MAINTENANCE','MANUTENCAO PREVENTIVA','MANUTENCAO CORRETIVA'].includes(s)) return 'maintenance';
    return 'unknown';
  };
  const active=[...current.values()], maint=active.filter(r=>maintenance.has(reason(r.reasonName))).map(publicRecord);
  return {updatedAt,equipment:[...new Set(rows.filter(r=>r.equipmentTag).map(r=>String(r.equipmentTag)))].sort().map(tag=>({equipmentTag:tag,operationalState:state(current.get(tag)),current:current.has(tag)?publicRecord(current.get(tag)!):null})),
    pauses:active.filter(r=>reason(r.reasonName)==='PAUSA ERGONOMICA').map(publicRecord),maintenance:maint,maintenanceAlerts:maint,records:rows.map(publicRecord)};
}