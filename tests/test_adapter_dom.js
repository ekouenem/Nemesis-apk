// Regression: a user's message is not a new assistant reply.
const fs=require('fs'), vm=require('vm'), assert=require('assert');
let items=[], assistantBlocks=[];
const document={querySelectorAll: selector => {
  if(selector==='.ds-assistant-message-main-content') return assistantBlocks;
  return selector.includes('.ds-message') ? items : [];
}, querySelector:()=>null};
const window={};
vm.runInNewContext(fs.readFileSync('nemapi/app/src/main/assets/js/adapter-deepseek.js','utf8'), {window,document,console});
const assistant=text=>({querySelector:s=>s==='.ds-assistant-message-main-content'?{innerText:text}:null});
const user=text=>({innerText:text,querySelector:()=>null});
function answer(){return JSON.parse(window.__napDS.answer());}
items=[user('question')]; assistantBlocks=[]; assert.strictEqual(answer().ok,false);
items=[assistant('old')]; assistantBlocks=[{innerText:'old'}]; assert.strictEqual(answer().text,'old'); assert.strictEqual(answer().blocks,1);
items=[assistant('old'),user('new question')]; assistantBlocks=[{innerText:'old'}]; assert.strictEqual(answer().text,'old'); assert.strictEqual(answer().blocks,1);
items=[assistant('old'),user('new question'),assistant('new answer')]; assistantBlocks=[{innerText:'old'},{innerText:'new answer'}]; assert.strictEqual(answer().text,'new answer'); assert.strictEqual(answer().blocks,2);
// Long transcript path: one direct assistant query, with no descendant search
// for each user/assistant message in the existing thread.
let descendantQueries=0;
items=Array.from({length:2000},()=>({querySelector:()=>{descendantQueries++;return null;}}));
assistantBlocks=[{innerText:'latest answer'}];
assert.strictEqual(answer().text,'latest answer'); assert.strictEqual(descendantQueries,0);
console.log('DOM assistant freshness: OK');
