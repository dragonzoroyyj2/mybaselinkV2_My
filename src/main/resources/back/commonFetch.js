/**
 * 🧩 commonFetch.js (실전 공통 유틸)
 * --------------------------------------------------------
 * ✅ CSRF + JWT 자동 포함 fetch wrapper
 * ✅ POST / PUT / DELETE / GET 공통 처리
 * ✅ window.notify 와 완전 호환
 * ✅ JSON 자동 직렬화 및 오류 처리 일원화
 * --------------------------------------------------------
 *
 * 💡 사용법:
 *   fnInsert('/api/stock/add', data, res => window.notify('success','등록완료'));
 *
 * 📅 업데이트: 2025-11-02 15:30 (Asia/Seoul)
 */

function getCsrfInfo() {
  const token = document.querySelector("meta[name='_csrf']")?.content;
  const header = document.querySelector("meta[name='_csrf_header']")?.content;
  return token && header ? { token, header } : {};
}

function makeHeaders(extra = {}) {
  const headers = {
    "Content-Type": "application/json",
    ...extra
  };

  const csrf = getCsrfInfo();
  if (csrf.header && csrf.token) headers[csrf.header] = csrf.token;

  const jwt = localStorage.getItem("accessToken");
  if (jwt) headers["Authorization"] = `Bearer ${jwt}`;

  return headers;
}

function handleResponse(res, actionText = "요청") {
  if (!res.ok) throw new Error(`${actionText} 실패 (${res.status})`);
  return res.text().then(t => {
    try { return t ? JSON.parse(t) : {}; }
    catch { return {}; }
  });
}

function fnGet(url, callback) {
  fetch(url, { method: "GET", headers: makeHeaders() })
    .then(res => handleResponse(res, "조회"))
    .then(callback)
    .catch(err => window.notify?.("error", err.message || "조회 실패"));
}

function fnInsert(url, data, callback) {
  fetch(url, { method: "POST", headers: makeHeaders(), body: JSON.stringify(data) })
    .then(res => handleResponse(res, "등록"))
    .then(callback)
    .catch(err => window.notify?.("error", err.message || "등록 실패"));
}

function fnUpdate(url, data, callback) {
  fetch(url, { method: "PUT", headers: makeHeaders(), body: JSON.stringify(data) })
    .then(res => handleResponse(res, "수정"))
    .then(callback)
    .catch(err => window.notify?.("error", err.message || "수정 실패"));
}

function fnDelete(url, ids, callback) {
  fetch(url, { method: "DELETE", headers: makeHeaders(), body: JSON.stringify(ids) })
    .then(res => handleResponse(res, "삭제"))
    .then(callback)
    .catch(err => window.notify?.("error", err.message || "삭제 실패"));
}

window.fnGet = fnGet;
window.fnInsert = fnInsert;
window.fnUpdate = fnUpdate;
window.fnDelete = fnDelete;
