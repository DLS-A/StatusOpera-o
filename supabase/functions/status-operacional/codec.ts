export async function pack(value:unknown):Promise<string> {
 const stream=new Blob([JSON.stringify(value)]).stream().pipeThrough(new CompressionStream('gzip'));
 const bytes=new Uint8Array(await new Response(stream).arrayBuffer());let text='';for(let i=0;i<bytes.length;i+=8192)text+=String.fromCharCode(...bytes.subarray(i,i+8192));return btoa(text);
}
export async function unpack(value:string):Promise<any> {
 if(value.length>4000000)throw new Error('Stored payload too large');
 const bytes=Uint8Array.from(atob(value),c=>c.charCodeAt(0));const stream=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
 const text=await new Response(stream).text();if(text.length>10000000)throw new Error('Stored payload too large');return JSON.parse(text);
}
