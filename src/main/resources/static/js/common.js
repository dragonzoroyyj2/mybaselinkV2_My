console.log("✅ common.js loaded");

function showToast(msg, type = "info") {
    alert(msg); // 간단히 alert로 대체. 추후 UI toast로 교체 가능
}

// ✅ CSRF 헤더 자동처리 (Security 활성화 시)
function getCookie(name) {
    const v = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
    return v ? v.pop() : '';
}

function makeHeaders() {
    const csrfHeaderName = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-XSRF-TOKEN';
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || getCookie('XSRF-TOKEN');
    const headers = {"Content-Type": "application/json"};
    if (csrfToken) headers[csrfHeaderName] = csrfToken;
    return headers;
}

// ✅ 공통 Ajax 함수
/*function fnInsert(url, data, callback) {
    fetch(url, {
        method: "POST",
        headers: makeHeaders(),
        body: JSON.stringify(data)
    })
        .then(res => {
            if (!res.ok) throw new Error("등록 실패 " + res.status);
            return res.json ? res.json() : {};
        })
        .then(callback)
        .catch(err => showToast(err.message, "error"));
}

function fnUpdate(url, data, callback) {
    fetch(url, {
        method: "PUT",
        headers: makeHeaders(),
        body: JSON.stringify(data)
    })
        .then(res => {
            if (!res.ok) throw new Error("수정 실패 " + res.status);
            return res.json ? res.json() : {};
        })
        .then(callback)
        .catch(err => showToast(err.message, "error"));
}

function fnDelete(url, ids, callback) {
    fetch(url, {
        method: "DELETE",
        headers: makeHeaders(),
        body: JSON.stringify(ids)
    })
        .then(res => {
            if (!res.ok) throw new Error("삭제 실패 " + res.status);
            return res.json ? res.json() : {};
        })
        .then(callback)
        .catch(err => showToast(err.message, "error"));
}*/
