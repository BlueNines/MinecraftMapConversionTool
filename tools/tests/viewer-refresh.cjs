const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../app/chunker/cli/src/main/resources/web/viewer-refresh.js'), 'utf8');

/** 按实际 BlueMap Promise/管理器接口构造可控制的加载器。 */
function fixture() {
  const pending = [], events = {}, replies = [];
  /** 每层最小视野有一个坐标，包含此前缓存的旧模型。 */
  function manager() {
    const layer = { tiles: new Map([[0, {model:'old'}]]), centerTile:{x:0,y:0}, viewDistanceX:0, viewDistanceZ:0, currentlyLoading:0, removed:0 };
    layer.tileLoader = {
      /** 只接受 x/z 两个参数，禁止回调式误用。 */
      load(x,z) { assert.equal(arguments.length,2); return new Promise((resolve,reject) => pending.push({resolve,reject})); }
    };
    /** 原管理器负责清空模型、缓存和遮罩。 */
    layer.removeAllTiles = function removeAllTiles() { this.removed++;this.tiles.clear(); };
    /** 模拟管理器追踪 Promise，并维护正在加载的计数。 */
    layer.loadAroundTile = function loadAroundTile() {
      this.currentlyLoading++;this.tiles.set(0,{loading:true});
      this.tileLoader.load(0,0).then(model => this.tiles.set(0,{model}), error => this.tiles.set(0,{error}))
        .finally(() => this.currentlyLoading--);
    };
    /** 本夹具的单个视野坐标已经由 loadAroundTile 加入队列。 */
    layer.loadNextTile = function loadNextTile() { return false; };
    return layer;
  }
  const high=manager(),low=manager(),pose={x:12,y:30,z:45};
  const viewer={map:{hiresTileManager:high,lowresTileManager:low},pose,
    /** 模拟内置缓存版本更新，后续进入视野的瓦片也使用新版本。 */
    clearTileCache(stamp){high.tileLoader.tileCacheHash=stamp;low.tileLoader.tileCacheHash=stamp;}
  };
  const parent={postMessage:data=>replies.push(data)};
  const window={parent,bluemap:{mapViewer:viewer},addEventListener:(name,callback)=>{events[name]=callback;}};
  const document={documentElement:{dataset:{}}};
  vm.runInNewContext(source,{window,document,setTimeout,Date,Promise,Map,Error});
  return {window,document,pending,high,low,pose,viewer,events,replies,parent};
}

test('两层全部完成后确认，并复用并发请求且保持镜头',async()=>{
  const f=fixture(), original=f.high.tileLoader.load;
  const first=f.window.__vantaloomRefreshTiles('test');
  assert.equal(first,f.window.__vantaloomRefreshTiles('again'));
  let done=false;first.then(()=>{done=true;});
  f.pending[0].resolve('new-high');await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(done,false);
  f.pending[1].resolve('new-low');const result=await first;
  assert.equal(result.ok,true);assert.equal(result.count,2);
  assert.equal(f.high.removed,1);assert.equal(f.low.removed,1);
  assert.equal(f.high.tileLoader.load,original);assert.ok(f.high.tileLoader.tileCacheHash);
  assert.equal(f.viewer.pose,f.pose);
});

test('旧模型变成空气时能清除，空记录不是加载失败',async()=>{
  const f=fixture(),promise=f.window.__vantaloomRefreshTiles('erase');
  f.pending[0].reject({status:'empty'});f.pending[1].reject({target:{status:404}});
  assert.equal((await promise).ok,true);assert.equal(f.high.tiles.get(0).model,undefined);
});

test('任一瓦片请求失败不得误报成功',async()=>{
  const f=fixture(),promise=f.window.__vantaloomRefreshTiles('failure');
  f.pending[0].resolve('new');f.pending[1].reject({target:{status:500}});
  assert.equal((await promise).ok,false);
});

test('握手重发不会在成功后再次清空同一版本的画面',async()=>{
  const f=fixture(),event={source:f.parent,origin:'http://127.0.0.1:8144',data:{type:'vantaloom:refresh-tiles',revision:2}};
  f.events.message(event);f.pending[0].resolve('new');f.pending[1].resolve('new');
  await new Promise(resolve=>setTimeout(resolve,120));
  assert.equal(f.replies[0].ok,true);assert.equal(f.document.documentElement.dataset.previewRevision,'2');
  f.events.message(event);assert.equal(f.high.removed,1);assert.equal(f.replies.length,2);
});

test('请求数暂时为零但加载队列还有内容时继续等待',async()=>{
  const f=fixture();let added=false;
  /** 引擎发现后续队列内容时实际启动加载，返回 true。 */
  f.high.loadNextTile=function loadNextTile(){
    if(!added&&this.currentlyLoading===0){added=true;this.loadAroundTile();return true;}
    return false;
  };
  const promise=f.window.__vantaloomRefreshTiles('queued');let done=false;promise.then(()=>{done=true;});
  f.pending[0].resolve('first');f.pending[1].resolve('low');
  await new Promise(resolve=>setTimeout(resolve,120));
  assert.equal(added,true);assert.equal(done,false);
  f.pending[2].resolve('last');assert.equal((await promise).ok,true);
});

test('临时加载失败后可以重试同一个版本',async()=>{
  const f=fixture(),event={source:f.parent,origin:'http://127.0.0.1:8144',data:{type:'vantaloom:refresh-tiles',revision:5}};
  f.events.message(event);f.pending[0].resolve('new');f.pending[1].reject({target:{status:500}});
  await new Promise(resolve=>setTimeout(resolve,120));assert.equal(f.replies[0].ok,false);
  f.events.message(event);f.pending[2].resolve('retry');f.pending[3].resolve('retry');
  await new Promise(resolve=>setTimeout(resolve,120));assert.equal(f.replies[1].ok,true);
});
