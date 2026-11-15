/* 星辰影城 · 管理端逻辑（原生 fetch，无框架/CDN；契约依据：字段表-A模块.md / -B模块.md / -C模块.md） */
'use strict';

/* ================= 基础设施：接口封装 / Toast / 横幅 ================= */

/* ISO-8601 时间展示：去掉 T 与亚秒（服务端含 .123456 形式），仅用于界面展示 */
function fmtTime(s) {
  if (!s) return '';
  return String(s).replace('T', ' ').replace(/\.\d+$/, '');
}

async function api(url, opts) {
  opts = opts || {};
  var init = { method: opts.method || 'GET', headers: {} };
  if (opts.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(opts.body);
  }
  var res, payload;
  try {
    res = await fetch(url, init);
    payload = await res.json();
  } catch (e) {
    payload = { code: 10000, message: '网络请求失败：' + e.message, data: null };
  }
  return { http: res ? res.status : 0, code: payload.code, message: payload.message, data: payload.data };
}

function errMsg(r) { return '[' + r.code + '] ' + (r.message || '未知错误'); }

var toastTimer = null;
function toast(msg) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { t.classList.remove('show'); }, 3200);
}

function showBanner(id, html) {
  var el = document.getElementById(id);
  el.innerHTML = html;
  el.classList.remove('hidden');
}
function hideBanner(id) { document.getElementById(id).classList.add('hidden'); }

/* 鉴权守卫：10001 → 引导登录；10002 → 引导对应角色登录。返回 true 表示非鉴权问题可继续提示 */
function authFail(r) {
  if (r.code === 10001) { showAuthBanner('请先登录管理端账号（10001 未登录）：排片员用「影片管理/排片管理/票价策略」，检票员用「检票核销」。'); return true; }
  if (r.code === 10002) { showAuthBanner('当前登录角色无权访问该功能区（10002 角色不符）：影片/排片/策略需<b>排片员 scheduler</b>，检票核销需<b>检票员 checker</b>，请切换登录。'); return true; }
  return false;
}

function showAuthBanner(html) {
  var el = document.getElementById('authBanner');
  el.innerHTML = html;
  el.classList.remove('hidden');
}
function hideAuthBanner() { document.getElementById('authBanner').classList.add('hidden'); }

/* ================= 全局状态 ================= */

var state = {
  user: null,          // { userId, username, role }
  role: null,          // SCHEDULER / CHECKER / AUDIENCE / null
  movieCache: {},      // id -> movie（含会话内下架的影片）
  movies: [],          // 最近一次 GET /api/movies 结果（ON_SHOW）
  showings: [],        // 最近一次 GET /api/admin/showings 结果
  halls: [],           // [{id,name}]（取自排片数据 + 本页新建）
  editingMovieId: null,
  strategyShowingId: null,
  pickedStrategy: 'ORIGINAL',
  ckLog: []
};

/* ================= 导航 ================= */

document.querySelectorAll('#adminNav button').forEach(function (b) {
  b.addEventListener('click', function () { goto(b.dataset.p); });
});

function goto(id) {
  document.querySelectorAll('#adminNav button').forEach(function (b) { b.classList.toggle('active', b.dataset.p === id); });
  document.querySelectorAll('section.page').forEach(function (s) { s.classList.toggle('active', s.id === id); });
  if (id === 'p-movies') loadMovies();
  if (id === 'p-showings') { loadShowings(); }
  if (id === 'p-strategy') initStrategyPage();
}

/* ================= 登录 / 会话 ================= */

document.getElementById('loginBtn').addEventListener('click', doLogin);
document.getElementById('loginPass').addEventListener('keydown', function (e) { if (e.key === 'Enter') doLogin(); });
document.getElementById('logoutBtn').addEventListener('click', doLogout);

async function doLogin() {
  var u = document.getElementById('loginUser').value.trim();
  var p = document.getElementById('loginPass').value;
  if (!u || !p) { showAuthBanner('请输入用户名与密码。演示账号：排片员 scheduler/scheduler123 · 检票员 checker/checker123'); return; }
  var r = await api('/api/auth/login', { method: 'POST', body: { username: u, password: p } });
  if (r.code !== 0) {
    var hint = r.code === 12007 ? '（12007 用户名或密码错误，请核对演示账号）' : '';
    showAuthBanner('登录失败：' + errMsg(r) + hint);
    return;
  }
  state.user = r.data;
  state.role = r.data.role;
  hideAuthBanner();
  renderSession();
  if (state.role === 'SCHEDULER') {
    toast('排片员 ' + state.user.username + ' 登录成功');
    goto('p-movies');
  } else if (state.role === 'CHECKER') {
    toast('检票员 ' + state.user.username + ' 登录成功');
    goto('p-checkin');
  } else {
    showAuthBanner('已登录为观众账号（' + state.user.username + '）：管理端接口将返回 10002 角色不符。请退出后改用 scheduler（排片员）或 checker（检票员）账号登录。');
  }
}

function doLogout() {
  /* 后端无登出接口（auth 仅注册/登录），此处仅清空本地会话状态 */
  state.user = null; state.role = null;
  renderSession();
  showAuthBanner('已退出本地会话状态。后端 Session 仍有效，重新登录即可覆盖。');
}

function renderSession() {
  var tag = document.getElementById('sessionTag');
  var loginBtn = document.getElementById('loginBtn');
  var logoutBtn = document.getElementById('logoutBtn');
  if (state.user) {
    var roleName = state.role === 'SCHEDULER' ? '排片员' : state.role === 'CHECKER' ? '检票员' : '观众';
    tag.textContent = roleName + ' · ' + state.user.username;
    tag.classList.remove('hidden');
    loginBtn.classList.add('hidden');
    logoutBtn.classList.remove('hidden');
  } else {
    tag.classList.add('hidden');
    loginBtn.classList.remove('hidden');
    logoutBtn.classList.add('hidden');
  }
  applyRoleGating();
}

/* 角色不符的功能区置灰（10002 的前端预引导，最终以后端校验为准） */
function applyRoleGating() {
  var btns = document.querySelectorAll('#adminNav button');
  var schedulerAreas = { 'p-movies': 0, 'p-showings': 1, 'p-strategy': 2 };
  btns.forEach(function (b) {
    var page = b.dataset.p;
    var locked = false;
    if (state.role === 'SCHEDULER' && page === 'p-checkin') locked = true;
    if (state.role === 'CHECKER' && schedulerAreas.hasOwnProperty(page)) locked = true;
    b.classList.toggle('locked', locked);
    b.title = locked ? '当前角色无权访问（10002）' : '';
  });
}

/* 页面加载：探测既有会话（无 /me 接口，用排片列表接口探测角色） */
async function restoreSession() {
  var r = await api('/api/admin/showings');
  if (r.code === 0) {
    state.role = 'SCHEDULER';
    state.user = { username: '（会话恢复）', role: 'SCHEDULER' };
    renderSession();
    hideAuthBanner();
    ingestShowings(r.data || []);
    loadMovies();
  } else if (r.code === 10002) {
    showAuthBanner('检测到已有登录会话，但角色不是排片员（10002）：若您是<b>检票员</b>，可直接进入「④ 检票核销」；否则请重新登录。');
  }
  /* 10001（未登录）保持静默，等用户登录或触发具体操作时再引导 */
}

/* ================= ① 影片管理 ================= */

function movieStatusTag(m) {
  return m.status === 'ON_SHOW'
    ? '<span class="tag paid">上映中</span>'
    : '<span class="tag off">已下架（本页缓存）</span>';
}

async function loadMovies() {
  if (!authPrecheck('movieBanner')) return;
  var r = await api('/api/movies');   // 登录即可访问，返回全部 ON_SHOW 影片
  if (r.code !== 0) {
    if (authFail(r)) return;
    showBanner('movieBanner', '影片列表加载失败：' + errMsg(r));
    return;
  }
  hideBanner('movieBanner');
  hideAuthBanner();
  /* 字段表-B模块：MovieBriefResponse 无 status 字段（接口语义即"仅返回 ON_SHOW 影片"），此处补标状态供表格展示 */
  state.movies = (r.data || []).map(function (m) { m.status = m.status || 'ON_SHOW'; return m; });
  state.movies.forEach(function (m) { state.movieCache[m.id] = m; });
  renderMovies();
}

/* 列表 = ON_SHOW（来自接口） + 会话内下架的缓存影片（置底） */
function renderMovies() {
  var tb = document.getElementById('movieTable');
  var offShow = [];
  Object.keys(state.movieCache).forEach(function (id) {
    var m = state.movieCache[id];
    if (m.status !== 'ON_SHOW') offShow.push(m);
  });
  var rows = state.movies.concat(offShow);
  document.getElementById('movieEmpty').classList.toggle('hidden', rows.length > 0);
  var html = '<tr><th>ID</th><th>片名</th><th>类型</th><th>片长</th><th>状态</th><th>操作</th></tr>';
  rows.forEach(function (m) {
    var ops = '<button class="btn small ghost" onclick="editMovie(' + m.id + ')">编辑</button> '
      + (m.status === 'ON_SHOW'
        ? '<button class="btn small danger" onclick="setMovieStatus(' + m.id + ',\'OFF_SHOW\')">下架</button>'
        : '<button class="btn small primary" onclick="setMovieStatus(' + m.id + ',\'ON_SHOW\')">上架</button>');
    html += '<tr><td class="num">' + m.id + '</td><td>' + esc(m.title) + '</td><td>' + esc(m.genre || '—')
      + '</td><td class="num">' + (m.durationMin ? m.durationMin + ' 分钟' : '—')
      + '</td><td>' + movieStatusTag(m) + '</td><td>' + ops + '</td></tr>';
  });
  tb.innerHTML = html;
}

function collectMovieForm() {
  var body = {};
  var title = document.getElementById('mvTitle').value.trim();
  var genre = document.getElementById('mvGenre').value.trim();
  var duration = document.getElementById('mvDuration').value.trim();
  var poster = document.getElementById('mvPoster').value.trim();
  if (title) body.title = title;
  if (genre) body.genre = genre;
  if (duration) body.durationMin = parseInt(duration, 10);
  if (poster) body.posterUrl = poster;
  return body;
}

async function saveMovie() {
  if (!authPrecheck('movieBanner')) return;
  var body = collectMovieForm();
  if (!body.title) { showBanner('movieBanner', '片名为必填项（10003）。'); return; }
  var r = await api('/api/admin/movies', { method: 'POST', body: body });
  if (r.code !== 0) {
    if (authFail(r)) return;
    showBanner('movieBanner', '新增影片失败：' + errMsg(r));
    return;
  }
  hideBanner('movieBanner');
  state.movieCache[r.data.id] = r.data;
  toast(r.message || '影片已创建并上架');
  loadMovies();
}

function editMovie(id) {
  var m = state.movieCache[id];
  if (!m) return;
  state.editingMovieId = id;
  document.getElementById('movieFormTitle').textContent = '编辑影片（ID ' + id + ' · 仅填写的字段会被更新）';
  document.getElementById('mvTitle').value = m.title || '';
  document.getElementById('mvGenre').value = m.genre || '';
  document.getElementById('mvDuration').value = m.durationMin || '';
  document.getElementById('mvPoster').value = m.posterUrl || '';
  document.getElementById('mvSaveBtn').classList.add('hidden');
  document.getElementById('mvUpdateBtn').classList.remove('hidden');
  document.getElementById('mvCancelEditBtn').classList.remove('hidden');
  hideBanner('movieBanner');
}

function cancelMovieEdit() {
  state.editingMovieId = null;
  document.getElementById('movieForm').reset();
  document.getElementById('movieFormTitle').textContent = '新增影片（创建即上架 ON_SHOW）';
  document.getElementById('mvSaveBtn').classList.remove('hidden');
  document.getElementById('mvUpdateBtn').classList.add('hidden');
  document.getElementById('mvCancelEditBtn').classList.add('hidden');
}

async function updateMovie() {
  if (!authPrecheck('movieBanner') || !state.editingMovieId) return;
  var body = collectMovieForm();
  if (Object.keys(body).length === 0) { showBanner('movieBanner', '请至少填写一个要修改的字段（接口为部分更新：null/缺省字段保持原值）。'); return; }
  var r = await api('/api/admin/movies/' + state.editingMovieId, { method: 'PUT', body: body });
  if (r.code !== 0) {
    if (authFail(r)) return;
    showBanner('movieBanner', '编辑影片失败：' + errMsg(r));
    return;
  }
  hideBanner('movieBanner');
  state.movieCache[r.data.id] = r.data;
  toast(r.message || '影片已更新');
  cancelMovieEdit();
  loadMovies();
}

async function setMovieStatus(id, status) {
  if (!authPrecheck('movieBanner')) return;
  var verb = status === 'OFF_SHOW' ? '下架' : '上架';
  if (!confirm('确认' + verb + '影片 ID ' + id + '？（下架不级联取消既有场次，但此后创建该影片场次会被 11007 拦截）')) return;
  var r = await api('/api/admin/movies/' + id, { method: 'PUT', body: { status: status } });
  if (r.code !== 0) {
    if (authFail(r)) return;
    showBanner('movieBanner', verb + '失败：' + errMsg(r));
    return;
  }
  hideBanner('movieBanner');
  state.movieCache[r.data.id] = r.data;
  toast((r.message || '成功') + '：影片已' + verb);
  loadMovies();
}

/* ================= ② 排片管理 ================= */

function authPrecheck(bannerId) {
  if (state.user) { hideBanner(bannerId); return true; }
  showBanner(bannerId, '请先在页首登录（排片员 scheduler/scheduler123）。');
  return false;
}

var PRICE_TYPE_CN = { ORIGINAL: '全场原价', MEMBER_DISCOUNT: '会员日折扣', ZONE_PRICING: '分区计价' };

function fmtParams(type, params) {
  if (!params) return '—';
  if (type === 'ORIGINAL') return '原价 ¥' + Number(params.basePrice).toFixed(2);
  if (type === 'MEMBER_DISCOUNT') return '¥' + Number(params.basePrice).toFixed(2) + ' × ' + params.discount;
  if (type === 'ZONE_PRICING') return '普通 ¥' + Number(params.normalPrice).toFixed(2) + ' / 黄金 ¥' + Number(params.goldenPrice).toFixed(2);
  return JSON.stringify(params);
}

function showingStatusTag(s) {
  if (s.status === 'PUBLISHED') return '<span class="tag paid">已发布</span>';
  if (s.status === 'CANCELED') return '<span class="tag refund">已取消</span>';
  return '<span class="tag used">' + esc(s.status) + '</span>';
}

function ingestShowings(list) {
  state.showings = list || [];
  /* 影厅字典：取自排片数据中的 (hallId, hallName) 去重 + 本页新建影厅 */
  var seen = {};
  state.halls.forEach(function (h) { seen[h.id] = true; });
  state.showings.forEach(function (s) {
    if (!seen[s.hallId]) { seen[s.hallId] = true; state.halls.push({ id: s.hallId, name: s.hallName }); }
  });
  renderHallSelects();
  renderShowingFilters();
  renderShowings();
}

async function loadShowings() {
  if (!authPrecheck('showingBanner')) return;
  var q = [];
  var mv = document.getElementById('fltMovie').value;
  var hl = document.getElementById('fltHall').value;
  var st = document.getElementById('fltStatus').value;
  if (mv) q.push('movieId=' + mv);
  if (hl) q.push('hallId=' + hl);
  if (st) q.push('status=' + st);
  var r = await api('/api/admin/showings' + (q.length ? '?' + q.join('&') : ''));
  if (r.code !== 0) {
    if (authFail(r)) return;
    showBanner('showingBanner', '排片列表加载失败：' + errMsg(r));
    return;
  }
  hideBanner('showingBanner');
  hideAuthBanner();
  ingestShowings(r.data || []);
  refreshMovieSelects();
}

function renderHallSelects() {
  var opts = state.halls.map(function (h) { return '<option value="' + h.id + '">' + esc(h.name) + '（ID ' + h.id + '）</option>'; }).join('');
  document.getElementById('sgHall').innerHTML = opts || '<option value="">（暂无影厅，请先新建）</option>';
  var fopts = '<option value="">全部</option>' + state.halls.map(function (h) { return '<option value="' + h.id + '">' + esc(h.name) + '</option>'; }).join('');
  document.getElementById('fltHall').innerHTML = fopts;
}

function renderShowingFilters() {
  var cur = document.getElementById('fltMovie').value;
  var opts = '<option value="">全部</option>' + state.movies.map(function (m) {
    return '<option value="' + m.id + '">' + esc(m.title) + '</option>';
  }).join('');
  var sel = document.getElementById('fltMovie');
  sel.innerHTML = opts;
  if (cur) sel.value = cur;
}

function refreshMovieSelects() {
  var opts = state.movies.filter(function (m) { return m.status === 'ON_SHOW'; }).map(function (m) {
    return '<option value="' + m.id + '">' + esc(m.title) + '（ID ' + m.id + (m.durationMin ? ' · ' + m.durationMin + '分钟' : '') + '）</option>';
  }).join('');
  document.getElementById('sgMovie').innerHTML = opts || '<option value="">（暂无上映中影片，请先到①新增）</option>';
}

function onCreatePriceTypeChange() {
  var v = document.getElementById('sgPriceType').value;
  var box = document.getElementById('sgParamBox');
  if (v === 'ZONE_PRICING') {
    box.innerHTML = '<label class="inline">黄金区 ¥<input type="number" id="sgGolden" step="0.01" min="0.01" value="45.00"></label>'
      + '<label class="inline">普通区 ¥<input type="number" id="sgNormal" step="0.01" min="0.01" value="35.00"></label>';
  } else {
    box.innerHTML = '<label class="inline">基础价 ¥<input type="number" id="sgBasePrice" step="0.01" min="0.01" value="38.00"></label>'
      + (v === 'MEMBER_DISCOUNT' ? '<label class="inline">折扣<input type="number" id="sgDiscount" step="0.01" min="0.01" max="0.99" value="0.80"></label>' : '')
      + '<span class="note">priceParams（含 basePrice）始终必填——无价场次不可售；留空策略类型提交可演示 3a 默认原价提示。</span>';
  }
}

function collectCreateParams(type) {
  if (type === 'ZONE_PRICING') {
    return { goldenPrice: parseFloat(document.getElementById('sgGolden').value), normalPrice: parseFloat(document.getElementById('sgNormal').value) };
  }
  var p = { basePrice: parseFloat(document.getElementById('sgBasePrice').value) };
  if (type === 'MEMBER_DISCOUNT') p.discount = parseFloat(document.getElementById('sgDiscount').value);
  return p;
}

async function createShowing() {
  if (!authPrecheck('showingBanner')) return;
  hideBanner('showingOkBanner');
  var movieId = parseInt(document.getElementById('sgMovie').value, 10);
  var hallId = parseInt(document.getElementById('sgHall').value, 10);
  var startRaw = document.getElementById('sgStart').value;
  var type = document.getElementById('sgPriceType').value;
  if (!movieId || !hallId || !startRaw) {
    showBanner('showingBanner', '影片、影厅、开映时间均为必填（10003）。');
    return;
  }
  var body = { movieId: movieId, hallId: hallId, startTime: startRaw.length === 16 ? startRaw + ':00' : startRaw, priceParams: collectCreateParams(type) };
  if (type) body.priceType = type;   // 缺省 priceType → 服务端 UC103-3a 默认套用全场原价
  var r = await api('/api/admin/showings', { method: 'POST', body: body });
  if (r.code !== 0) {
    if (authFail(r)) return;
    var why = '';
    if (r.code === 11002) why = '（UC103-2a：该影厅该时段已有场次）';
    if (r.code === 11007) why = '（UC103-5a：影片已下架，终止创建场次）';
    if (r.code === 11003) why = '（UC103-3 / UC105-3a：票价策略参数非法）';
    showBanner('showingBanner', '创建场次失败：' + errMsg(r) + why);
    return;
  }
  hideBanner('showingBanner');
  /* 3a 命中时服务端 message 为「排片成功；未配置票价策略，已默认套用全场原价」，原样展示 */
  showBanner('showingOkBanner', esc(r.message || '排片成功') + ' —— 新场次 ID ' + r.data.id + '：' + esc(r.data.movieTitle)
    + ' @ ' + esc(r.data.hallName) + ' ' + esc(fmtTime(r.data.startTime))
    + ' · ' + PRICE_TYPE_CN[r.data.priceType] + '（' + fmtParams(r.data.priceType, r.data.priceParams) + '）'
    + ' · 座位 ' + r.data.totalSeats + '（空 ' + r.data.freeSeats + '）');
  toast(r.message || '排片成功');
  await loadShowings();
}

function renderShowings() {
  var tb = document.getElementById('showingTable');
  document.getElementById('showingEmpty').classList.toggle('hidden', state.showings.length > 0);
  var html = '<tr><th>ID</th><th>影片</th><th>影厅</th><th>开映时间</th><th>票价策略</th><th>座位（总/空/锁/售）</th><th>状态</th><th>操作</th></tr>';
  state.showings.forEach(function (s) {
    var ops = '';
    if (s.status === 'PUBLISHED') {
      ops = '<button class="btn small ghost" onclick="jumpStrategy(' + s.id + ')">策略</button> '
          + '<button class="btn small danger" onclick="cancelShowing(' + s.id + ')">取消场次</button>';
    }
    html += '<tr><td class="num">' + s.id + '</td><td>' + esc(s.movieTitle) + '</td><td>' + esc(s.hallName)
      + '</td><td class="num">' + esc(fmtTime(s.startTime))
      + '</td><td>' + PRICE_TYPE_CN[s.priceType] + '<br><span class="note" style="margin-top:2px">' + fmtParams(s.priceType, s.priceParams) + '</span>'
      + '</td><td><span class="seatstat"><i class="free">空 ' + s.freeSeats + '</i><i class="locked">锁 ' + s.lockedSeats + '</i><i class="sold">售 ' + s.soldSeats + '</i><i>' + s.totalSeats + ' 座</i></span>'
      + '</td><td>' + showingStatusTag(s) + '</td><td>' + (ops || '—') + '</td></tr>';
  });
  tb.innerHTML = html;
}

async function cancelShowing(id) {
  if (!authPrecheck('showingBanner')) return;
  if (!confirm('确认取消场次 ID ' + id + '？（存在已售或锁定中座位将被 11004 拒绝）')) return;
  var r = await api('/api/admin/showings/' + id + '/cancel', { method: 'PUT' });
  if (r.code !== 0) {
    if (authFail(r)) return;
    var why = '';
    if (r.code === 11004) why = '（I14：场次存在已售或锁定中的座位，禁止取消）';
    if (r.code === 11006) why = '（仅 PUBLISHED 状态可取消）';
    showBanner('showingBanner', '取消场次失败：' + errMsg(r) + why);
    return;
  }
  hideBanner('showingBanner');
  toast('场次 ' + id + ' 已取消（状态 CANCELED，座位/订单/候补不级联改动）');
  await loadShowings();
}

function jumpStrategy(id) {
  goto('p-strategy');
  initStrategyPage(id);
}

/* 新建影厅（UC102，POST /api/admin/halls） */
function toggleHallForm() { document.getElementById('hallFormBox').classList.toggle('hidden'); }

async function createHall() {
  if (!authPrecheck('showingBanner')) return;
  var name = document.getElementById('hallName').value.trim();
  var type = document.getElementById('hallType').value;
  if (!name) { showBanner('showingBanner', '影厅名称为必填（10003）。'); return; }
  var r = await api('/api/admin/halls', { method: 'POST', body: { name: name, hallType: type } });
  if (r.code !== 0) {
    if (authFail(r)) return;
    showBanner('showingBanner', '新建影厅失败：' + errMsg(r));
    return;
  }
  hideBanner('showingBanner');
  state.halls.push({ id: r.data.id, name: r.data.name });
  renderHallSelects();
  document.getElementById('hallName').value = '';
  toast('影厅已创建：' + r.data.name + ' · ' + r.data.hallType + ' ' + r.data.rows + '×' + r.data.cols + '=' + r.data.seatCount + ' 座');
}

/* ================= ③ 票价策略 ================= */

function initStrategyPage(pickId) {
  document.getElementById('strategyOkBanner').classList.add('hidden');
  var sel = document.getElementById('stShowing');
  var published = state.showings.filter(function (s) { return s.status === 'PUBLISHED'; });
  if (!state.showings.length) {
    /* 策略页可能先于排片页打开：主动拉一次 */
    api('/api/admin/showings').then(function (r) {
      if (r.code === 0) { ingestShowings(r.data || []); initStrategyPage(pickId); }
      else if (!authFail(r)) showBanner('strategyBanner', '场次列表加载失败：' + errMsg(r));
    });
    sel.innerHTML = '<option value="">（加载中…）</option>';
    return;
  }
  var opts = published.map(function (s) {
    return '<option value="' + s.id + '">' + s.id + ' · ' + esc(s.movieTitle) + ' · ' + esc(s.hallName) + ' · ' + esc(fmtTime(s.startTime)) + '</option>';
  }).join('');
  sel.innerHTML = opts || '<option value="">（无已发布场次）</option>';
  if (pickId) sel.value = pickId;
  state.strategyShowingId = sel.value ? parseInt(sel.value, 10) : null;
  onStrategyShowingChange();
}

function currentStrategyShowing() {
  var id = parseInt(document.getElementById('stShowing').value, 10);
  if (!id) return null;
  return state.showings.filter(function (s) { return s.id === id; })[0] || null;
}

function onStrategyShowingChange() {
  var s = currentStrategyShowing();
  var cur = document.getElementById('stCurrent');
  if (!s) { cur.textContent = '当前策略：—'; return; }
  state.strategyShowingId = s.id;
  cur.innerHTML = '当前策略：<b>' + PRICE_TYPE_CN[s.priceType] + '</b>（' + fmtParams(s.priceType, s.priceParams) + '）· 座位 ' + s.totalSeats
    + ' · 场次 ID ' + s.id + ' · ' + esc(fmtTime(s.startTime));
  /* 预填当前策略档位与参数，便于微调 */
  pickStrategy(s.priceType);
  if (s.priceParams) {
    if (s.priceType === 'ORIGINAL') document.getElementById('stOriginalBase').value = s.priceParams.basePrice;
    if (s.priceType === 'MEMBER_DISCOUNT') {
      document.getElementById('stMemberBase').value = s.priceParams.basePrice;
      document.getElementById('stMemberDiscount').value = s.priceParams.discount;
    }
    if (s.priceType === 'ZONE_PRICING') {
      document.getElementById('stGolden').value = s.priceParams.goldenPrice;
      document.getElementById('stNormal').value = s.priceParams.normalPrice;
    }
  }
}

function pickStrategy(type) {
  state.pickedStrategy = type;
  document.querySelectorAll('.strategy').forEach(function (el) {
    el.classList.toggle('on', el.dataset.type === type);
  });
}

function collectStrategyParams() {
  if (state.pickedStrategy === 'ORIGINAL') {
    return { basePrice: parseFloat(document.getElementById('stOriginalBase').value) };
  }
  if (state.pickedStrategy === 'MEMBER_DISCOUNT') {
    return { basePrice: parseFloat(document.getElementById('stMemberBase').value), discount: parseFloat(document.getElementById('stMemberDiscount').value) };
  }
  return { goldenPrice: parseFloat(document.getElementById('stGolden').value), normalPrice: parseFloat(document.getElementById('stNormal').value) };
}

async function applyStrategy() {
  if (!authPrecheck('strategyBanner')) return;
  hideBanner('strategyOkBanner');
  var id = parseInt(document.getElementById('stShowing').value, 10);
  if (!id) { showBanner('strategyBanner', '请先选择场次。'); return; }
  var body = { priceType: state.pickedStrategy, priceParams: collectStrategyParams() };
  var r = await api('/api/admin/showings/' + id + '/strategy', { method: 'PUT', body: body });
  if (r.code !== 0) {
    if (authFail(r)) return;
    var why = r.code === 11003 ? '（UC105-3a：票价策略参数非法——基础价须>0，折扣系数 ∈ (0,1) 开区间，分区两档价均须>0）' : '';
    showBanner('strategyBanner', '策略应用失败：' + errMsg(r) + why);
    return;
  }
  hideBanner('strategyBanner');
  renderExamplePrices(r.data);
  showBanner('strategyOkBanner', '策略已生效（即时生效，已出票订单按快照价不受影响）：场次 ' + id + ' → ' + PRICE_TYPE_CN[r.data.priceType]);
  toast('票价策略已切换：' + PRICE_TYPE_CN[r.data.priceType]);
  /* 同步排片列表中的策略展示 */
  state.showings.forEach(function (s) {
    if (s.id === id) { s.priceType = r.data.priceType; s.priceParams = r.data.priceParams; }
  });
  renderShowings();
  onStrategyShowingChange();
}

/* UC105-4：examplePrices 示例价预览（键为分区名：ZONE_PRICING 两行，全场类仅 NORMAL） */
function renderExamplePrices(data) {
  document.getElementById('exampleEmpty').classList.add('hidden');
  var tb = document.getElementById('examplePriceTable');
  var ex = data.examplePrices || {};
  var rows = '';
  var names = { NORMAL: '普通区 NORMAL', GOLDEN: '黄金区 GOLDEN' };
  ['GOLDEN', 'NORMAL'].forEach(function (k) {
    if (ex[k] !== undefined) {
      rows += '<tr><td>' + names[k] + '</td><td>' + PRICE_TYPE_CN[data.priceType] + '</td><td class="num">¥' + Number(ex[k]).toFixed(2) + '</td></tr>';
    }
  });
  if (!rows) rows = '<tr><td colspan="3" class="empty">服务端未返回示例价</td></tr>';
  tb.innerHTML = '<tr><th>座位分区</th><th>已生效策略</th><th>示例票价</th></tr>' + rows
    + '<tr><td colspan="3"><span class="note" style="margin:0">场次 ID ' + data.showingId + ' · priceType=' + data.priceType + ' · priceParams=' + esc(JSON.stringify(data.priceParams)) + '</span></td></tr>';
}

/* ================= ④ 检票核销 ================= */

function onCkModeChange() {
  var mode = document.querySelector('input[name="ckMode"]:checked').value;
  var input = document.getElementById('ckValue');
  input.placeholder = mode === 'pickupCode' ? '输入 8 位取票码，如 86420195' : '输入订单号，如 B2026…（22 位）';
  input.maxLength = mode === 'pickupCode' ? 8 : 22;
}

async function doCheckin() {
  if (!authPrecheck('checkinBanner')) return;
  var mode = document.querySelector('input[name="ckMode"]:checked').value;
  var v = document.getElementById('ckValue').value.trim();
  if (!v) { showBanner('checkinBanner', '请输入核销凭据（8 位取票码或订单号，二者传其一）。'); return; }
  var body = mode === 'pickupCode' ? { pickupCode: v } : { orderNo: v };
  var btn = document.getElementById('ckBtn');
  btn.disabled = true;
  var r = await api('/api/admin/checkin', { method: 'POST', body: body });
  btn.disabled = false;
  renderCheckinResult(r, mode, v);
  pushCkLog(mode, v, r);
}

function renderCheckinResult(r, mode, v) {
  var card = document.getElementById('ckResultCard');
  var box = document.getElementById('ckResult');
  card.classList.remove('hidden');
  if (r.code === 0) {
    var d = r.data;
    var seatLine = (d.seats || []).map(function (s) {
      return s.rowNum + '排' + s.colNum + '座（' + s.zone + '）¥' + Number(s.price).toFixed(2);
    }).join('、');
    box.innerHTML = '<div class="ck-result pass"><div class="verdict">✔ 验票通过，欢迎入场</div>'
      + '<div class="meta">订单号 <b>' + esc(d.orderNo) + '</b> · 取票码 <b>' + esc(d.pickupCode) + '</b> · 状态 <b>' + esc(d.status) + '</b></div>'
      + '<div class="meta">影片 <b>《' + esc(d.movieTitle) + '》</b> · ' + esc(d.hallName) + ' · 开映 ' + esc(fmtTime(d.startTime)) + '</div>'
      + '<div class="meta">座位：<b>' + esc(seatLine || '—') + '</b></div>'
      + '<div class="meta">核销时间 ' + esc(fmtTime(d.checkinTime)) + ' · 操作员 <b>' + esc(d.operator) + '</b> · 订单 ID ' + d.orderId + ' · 场次 ID ' + d.showingId + '</div></div>';
    showBanner('checkinBanner', '');
    document.getElementById('checkinBanner').classList.add('hidden');
    toast('验票通过，欢迎入场');
  } else {
    if (authFail(r)) { box.innerHTML = ''; return; }
    var cls = 'fail', verdict = '✘ 核销失败';
    if (r.code === 13009) { cls = 'manual'; verdict = '⚠ 转人工窗口处理'; }
    var hint = '';
    if (r.code === 13008) hint = '<br><span class="note" style="margin:0">UC307-5a：订单已检票，重复核销幂等拒绝（并发核销仅一次生效）。</span>';
    if (r.code === 13007) hint = '<br><span class="note" style="margin:0">UC307-3a：仅「已出票（ISSUED）」订单可核销（待支付/已退票/已取消/已检票均拒绝）。</span>';
    if (r.code === 13009) hint = '<br><span class="note" style="margin:0">UC307-3b：开映已超过 30 分钟宽限期，请引导观众至人工窗口处理。</span>';
    if (r.code === 12004) hint = '<br><span class="note" style="margin:0">凭据检索不到对应订单：请核对 8 位取票码 / 22 位订单号是否输错。</span>';
    if (r.code === 10003) hint = '<br><span class="note" style="margin:0">orderNo 与 pickupCode 至少传其一（当前输入为空或格式不符）。</span>';
    box.innerHTML = '<div class="ck-result ' + cls + '"><div class="verdict">' + verdict + '</div>'
      + '<div class="meta">凭据（' + (mode === 'pickupCode' ? '取票码' : '订单号') + '）：<b>' + esc(v) + '</b></div>'
      + '<div class="meta">服务端响应：' + esc(errMsg(r)) + '</div>' + hint + '</div>';
  }
}

function pushCkLog(mode, v, r) {
  state.ckLog.unshift({
    time: new Date().toLocaleTimeString(),
    mode: mode === 'pickupCode' ? '取票码' : '订单号',
    value: v,
    ok: r.code === 0,
    detail: r.code === 0 ? (r.data.movieTitle + ' · ' + (r.data.seats || []).map(function (s) { return s.rowNum + '排' + s.colNum + '座'; }).join('、')) : errMsg(r)
  });
  var tb = document.getElementById('ckLogTable');
  document.getElementById('ckLogEmpty').classList.toggle('hidden', state.ckLog.length > 0);
  var html = '<tr><th>时间</th><th>凭据类型</th><th>凭据</th><th>结果</th><th>明细</th></tr>';
  state.ckLog.forEach(function (e) {
    html += '<tr><td class="num">' + e.time + '</td><td>' + e.mode + '</td><td class="num">' + esc(e.value) + '</td><td>'
      + (e.ok ? '<span class="tag paid">通过</span>' : '<span class="tag refund">拒绝</span>') + '</td><td>' + esc(e.detail) + '</td></tr>';
  });
  tb.innerHTML = html;
}

/* ================= 工具 ================= */

function esc(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/* ================= 启动 ================= */

onCkModeChange();
applyRoleGating();
restoreSession();
