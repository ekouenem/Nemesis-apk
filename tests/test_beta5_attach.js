// 50 MiB streamed in chunks through the real adapter, without a network call.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),crypto=require('crypto');
let input={files:null,getAttribute:()=>'',dispatchEvent:()=>{}};
const document={querySelectorAll:s=>s==="input[type='file']"?[input]:[],querySelector:()=>null};
class DataTransfer { constructor(){this.files=[];this.items={add:x=>this.files.push(x)}} }
class File { constructor(parts,name,opts){this.parts=parts;this.name=name;this.type=opts.type;this.size=parts.reduce((n,p)=>n+p.length,0)} }
const context={window:{},document,DataTransfer,File,Event:class {}, atob:x=>Buffer.from(x,'base64').toString('binary'),Uint8Array,console};
vm.runInNewContext(fs.readFileSync('nemapi/app/src/main/assets/js/adapter-deepseek.js','utf8'),context);
const ds=context.window.__napDS; assert(JSON.parse(ds.attachBegin()).ok);
const size=50*1024*1024, block=Buffer.alloc(72000); for(let i=0;i<block.length;i++) block[i]=i%251;
const expected=crypto.createHash('sha256'); let off=0;
while(off<size){ const n=Math.min(block.length,size-off),data=block.subarray(0,n); expected.update(data);
 const result=JSON.parse(ds.attachChunk(JSON.stringify({first:off===0,mime:'application/pdf',name:'fifty.pdf',b64:data.toString('base64')}))); assert(result.ok); off+=n; }
const result=JSON.parse(ds.attachCommit()); assert(result.ok); assert.strictEqual(input.files.length,1);
const file=input.files[0]; assert.strictEqual(file.size,size); const actual=crypto.createHash('sha256'); for(const part of file.parts)actual.update(part);
assert.strictEqual(actual.digest('hex'),expected.digest('hex')); assert.strictEqual(file.name,'fifty.pdf');
console.log('Fragmented 50 MiB adapter transfer: OK');
