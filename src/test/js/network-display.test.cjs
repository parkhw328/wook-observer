const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), vm = require('node:vm');
const context = {document:{addEventListener(){}}, esc: v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])), number:String, date:String};
vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/static/network.js','utf8'),context);
const row=(state,protocol='TCP',extras={})=>({socket:{protocol,state,localAddress:'127.0.0.1',localPort:443,peerAddress:'192.0.2.5',peerPort:50000,source:'ss'},owners:[],...extras});
const filter=(rows,f)=>context.filterNetworkEntries(rows,{state:'ALL',query:'',...f});
test('separates TCP states and UDP without discarding unknown process ownership',()=>{
 const rows=[row('LISTEN'),row('ESTABLISHED'),row('TIME_WAIT'),row('UNCONN','UDP')];
 assert.equal(filter(rows,{}).length,4);assert.equal(filter(rows,{state:'ESTABLISHED'}).length,1);
 assert.equal(filter(rows,{state:'OTHER_TCP'})[0].socket.state,'TIME_WAIT');assert.equal(filter(rows,{state:'UDP'}).length,1);
 assert.equal(filter(rows,{system:true}).length,4);
});
test('applies independent evidence filters and searches peer addresses and all PIDs',()=>{
 const rows=[row('ESTABLISHED','TCP',{systemService:true}),row('ESTABLISHED','TCP',{reservedPort:true}),row('ESTABLISHED','TCP',{ephemeralRange:true}),row('ESTABLISHED','TCP',{owners:[{pid:123456,name:'app'}],reservedPort:null,ephemeralRange:null})];
 assert.equal(filter(rows,{system:true,reserved:true,ephemeral:true}).length,1);
 assert.equal(filter(rows,{query:'192.0.2.5'}).length,4);assert.equal(filter(rows,{query:'123456'}).length,1);
 assert.equal(filter(rows,{state:'LISTEN'}).length,0);
});
test('keeps network preview independent of upload form and escapes hostile evidence',()=>{
 const coverage={status:'COLLECTED',message:'safe',count:1};
 const data={collectedAt:'2026-11-17',bindings:coverage,connections:coverage,settings:[],entries:[row('ESTABLISHED','TCP',{owners:[{pid:1,name:'<img src=x onerror=alert(1)>'}]})]};
 const html=context.networkPanel(data,'preview');
 assert.ok(!html.includes('<form'));assert.ok(!html.includes('<img'));assert.ok(html.includes('&lt;img'));
 assert.ok(html.includes('192.0.2.5:50000'));assert.ok(html.includes('포트 정책 일부 미확인'));
});
