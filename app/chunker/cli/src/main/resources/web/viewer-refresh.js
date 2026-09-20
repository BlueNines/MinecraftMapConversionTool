/* 完整刷新当前视野的瓦片缓存，服务器渲染仍然只处理变化的区块。 */
(function installTileRefresh() {
  'use strict';
  const state = window.__vantaloomViewer || (window.__vantaloomViewer = {});
  const live = { pending: null, refreshes: 0, lastTileCount: 0 };
  state.live = live;
  let acknowledged = null;

  /** 空记录和不存在的瓦片是有效结果，其余网络错误不能算作完成。 */
  function isEmpty(error) {
    return error && (error.status === 'empty' || error.status === 404 || error.target?.status === 404);
  }

  /** 同一时刻的重复请求共用一个任务，不返回虚假的“已刷新 0 个”。 */
  function refreshTiles(reason) {
    if (live.pending) return live.pending;
    live.reason = reason;
    live.pending = new Promise(function refresh(resolve) {
      const deadline = Date.now() + 45000;
      let viewer, map;
      const layers = [];
      let errors = 0, initialized = false;

      /** 只恢复加载函数，保留新缓存版本供以后移到视野中的瓦片使用。 */
      function finish(ok, error) {
        for (const layer of layers) {
          if (layer.loader.load === layer.wrapped) layer.loader.load = layer.original;
        }
        const count = layers.reduce(function countTiles(total, layer) { return total + layer.manager.tiles.size; }, 0);
        live.lastTileCount = count;
        if (ok) live.refreshes++;
        resolve({ ok, count, error: error || '' });
      }

      /** 使用管理器自己的卸载/加载流程，连空瓦片缓存和远近层一并失效。 */
      function initialize() {
        viewer = window.bluemap?.mapViewer;
        map = viewer?.map;
        if (!map) return false;
        const low = Array.isArray(map.lowresTileManager) ? map.lowresTileManager : [map.lowresTileManager];
        const managers = [map.hiresTileManager, ...low].filter(Boolean);
        if (!managers.length) return false;
        if (typeof viewer.clearTileCache !== 'function' || managers.some(function unsupported(manager) {
          return !manager.tiles || typeof manager.tiles.clear !== 'function'
            || typeof manager.removeAllTiles !== 'function' || typeof manager.loadAroundTile !== 'function'
            || typeof manager.loadNextTile !== 'function'
            || !manager.tileLoader || typeof manager.tileLoader.load !== 'function';
        })) throw new Error('当前预览版本不支持可靠的瓦片缓存更新。');
        viewer.clearTileCache(String(Date.now()));
        for (const manager of managers) {
          const loader = manager.tileLoader, original = loader.load;
          /** BlueMap 1.5.5 的真实接口是 Promise；记录错误后交回原管理器处理。 */
          function wrapped(x, z) {
            return original.call(loader, x, z).catch(function loadFailed(error) {
              if (!isEmpty(error)) errors++;
              throw error;
            });
          }
          layers.push({ manager, loader, original, wrapped });
          loader.load = wrapped;
          manager.removeAllTiles();
          manager.loadAroundTile(manager.centerTile.x, manager.centerTile.y, manager.viewDistanceX, manager.viewDistanceZ);
        }
        return true;
      }

      /** 由引擎确认加载队列已耗尽，不能靠估算视野面积或某一帧的请求数。 */
      function check() {
        try {
          if (Date.now() >= deadline) { finish(false, '瓦片加载超时，画面尚未全部更新。'); return; }
          if (!initialized) initialized = initialize();
          if (initialized) {
            if (viewer.map !== map) { finish(false, '预览地图已切换。'); return; }
            const complete = layers.every(function settled(layer) {
              const manager = layer.manager;
              return !manager.unloaded && !manager.loadNextTile() && manager.currentlyLoading === 0;
            });
            if (complete) { finish(errors === 0, errors ? errors + ' 个瓦片加载失败。' : ''); return; }
          }
          setTimeout(check, 80);
        } catch (error) { finish(false, error.message); }
      }
      check();
    }).finally(function releaseRefresh() { live.pending = null; });
    return live.pending;
  }

  window.__vantaloomRefreshTiles = refreshTiles;
  /** 只有所有瓦片就绪才确认这个版本；重复消息复用任务。 */
  window.addEventListener('message', function requested(event) {
    const request = event.data;
    if (event.source !== window.parent || !request || request.type !== 'vantaloom:refresh-tiles') return;
    if (acknowledged?.revision === request.revision) {
      event.source.postMessage(acknowledged, event.origin); return;
    }
    refreshTiles(request.reason).then(function reply(result) {
      if (result.ok) document.documentElement.dataset.previewRevision = String(request.revision);
      const reply = { type: 'vantaloom:tiles-refreshed', revision: request.revision, ...result };
      acknowledged = result.ok ? reply : null;
      event.source.postMessage(reply, event.origin);
    });
  });
})();
