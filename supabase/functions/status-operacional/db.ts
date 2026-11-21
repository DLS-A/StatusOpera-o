import postgres from 'npm:postgres@3.4.7';
export const sql = postgres(Deno.env.get('EDGE_DATABASE_URL')!, {
  max:1, prepare:false, idle_timeout:10, connect_timeout:10,
  ssl:{rejectUnauthorized:true,ca:Deno.env.get('EDGE_DATABASE_CA')},
  connection:{statement_timeout:10000,lock_timeout:3000},
});
export async function digest(value: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)))).map(v=>v.toString(16).padStart(2,'0')).join('');
}
export function randomSecret(): string {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}
export {HttpError} from './errors.ts';
