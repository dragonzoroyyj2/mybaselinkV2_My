/**
 * 🧩 commonUnifiedList.js
 * --------------------------------------------------------
 * ✅ 공용 리스트/CRUD/엑셀 + 반응형 테이블 + 페이징 자동조정
 * --------------------------------------------------------
 *
 * 사용법:
 *   initUnifiedList({ mode: "server" | "client", ...config });
 *
 * 주요 기능:
 *   1. 리스트 조회 및 페이징 (서버 / 클라이언트 모드 지원)
 *   2. 검색 기능 (엔터/버튼)
 *   3. 체크박스 전체 선택/해제
 *   4. 모달 등록/상세/수정
 *   5. 선택 삭제
 *   6. 엑셀 다운로드
 *   7. 화면 리사이즈에 따른 페이징 버튼 조정
 *
 * ⚠️ 이 파일만 교체하세요.
 */

function initUnifiedList(config) {
  const {
    mode = "server",
    apiUrl,
    tableBodySelector,
    paginationSelector,
    searchInputSelector,
    searchBtnSelector,
    addBtnSelector,
    modalId,
    saveBtnSelector,
    closeBtnSelector,
    checkAllSelector,
    deleteSelectedBtnSelector,
    detailModalId,
    detailFields,
    updateBtnSelector,
    excelBtnSelector,
    columns,
    pageSize: configPageSize,
    pageGroupSize: configGroupSize
  } = config;

  let currentPage = 0;
  const pageSize = configPageSize || 10;
  let totalPagesCache = 0;

  const $ = sel => document.querySelector(sel);
  const $$ = sel => document.querySelectorAll(sel);

  // 클라이언트 모드 전체 데이터
  let fullDataCache = [];
  let isFullDataLoaded = false;

  const csrfToken = document.querySelector("meta[name='_csrf']")?.content;
  const csrfHeader = document.querySelector("meta[name='_csrf_header']")?.content;
  const fetchOptions = (method, body) => {
    const opt = { method, headers: { "Content-Type": "application/json" } };
    if (csrfToken && csrfHeader) opt.headers[csrfHeader] = csrfToken;
    if (body) opt.body = JSON.stringify(body);
    const token = localStorage.getItem("token");
    if (token) opt.headers["Authorization"] = "Bearer " + token;
    return opt;
  };

  // ===============================
  // 데이터 로드
  // ===============================
  async function loadList(page = 0) {
    currentPage = page;
    const search = $(searchInputSelector)?.value || "";

    if (mode === "server") {
      const url = `${apiUrl}?page=${page}&size=${pageSize}&search=${encodeURIComponent(search)}`;
      try {
        const res = await fetch(url, fetchOptions("GET"));
        if (!res.ok) throw new Error("데이터 조회 실패");
        const data = await res.json();
        const content = data.content || [];
        const totalPages = data.totalPages ?? Math.ceil((data.totalElements ?? content.length) / pageSize);
        renderTable(content);
        totalPagesCache = totalPages;
        renderPagination(currentPage, totalPages, paginationSelector, loadList, configGroupSize);
        const totalCountEl = document.getElementById("totalCount");
        if (totalCountEl) totalCountEl.textContent = `총 ${data.totalElements ?? (totalPages * pageSize)}건`;
      } catch (err) {
        console.error(err);
        alert("데이터 조회 중 오류 발생");
      }
      return;
    }

    // ===============================
    // 클라이언트 모드
    // ===============================
    if (mode === "client") {
      try {
        if (!isFullDataLoaded) {
          const res = await fetch(`${apiUrl}`, fetchOptions("GET"));
          if (!res.ok) throw new Error("전체 데이터 조회 실패");
          const json = await res.json();
          if (Array.isArray(json)) fullDataCache = json;
          else if (Array.isArray(json.content)) fullDataCache = json.content;
          else fullDataCache = [];
          isFullDataLoaded = true;
        }

        const searchLower = search.toLowerCase();
        const filtered = search
          ? fullDataCache.filter(item => Object.values(item).some(v => String(v ?? "").toLowerCase().includes(searchLower)))
          : fullDataCache;

        const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
        const start = page * pageSize;
        const pageData = filtered.slice(start, start + pageSize);

        renderTable(pageData);
        totalPagesCache = totalPages;
        renderPagination(currentPage, totalPages, paginationSelector, loadList, configGroupSize);

        const totalCountEl = document.getElementById("totalCount");
        if (totalCountEl) totalCountEl.textContent = `총 ${filtered.length}건`;
      } catch (err) {
        console.error(err);
        alert("데이터 로드 실패 (client mode)");
      }
      return;
    }

    console.warn("알 수 없는 mode:", mode);
  }

  // ===============================
  // 테이블 렌더링
  // ===============================
  function renderTable(list) {
    const tbody = $(tableBodySelector);
    if (!tbody) return;
    tbody.innerHTML = "";

    if (!Array.isArray(list) || list.length === 0) {
      tbody.innerHTML = `<tr><td colspan="${columns.length + 1}">데이터가 없습니다.</td></tr>`;
      return;
    }

    list.forEach(row => {
      const tr = document.createElement("tr");

      const chkTd = document.createElement("td");
      chkTd.innerHTML = `<input type="checkbox" value="${row.id}">`;
      tr.appendChild(chkTd);

      columns.forEach(col => {
        const td = document.createElement("td");
        const val = row[col.key] ?? "";
        if (col.isDetailLink) {
          td.innerHTML = `<a href="#" data-id="${row.id}" class="detail-link">${val}</a>`;
        } else td.textContent = val;
        tr.appendChild(td);
      });

      tbody.appendChild(tr);
    });

    // 상세보기 링크 이벤트
    $$(".detail-link").forEach(a => {
      a.addEventListener("click", e => {
        e.preventDefault();
        const anchor = e.target.closest(".detail-link");
        if (!anchor) return;
        const id = anchor.dataset.id;
        openDetailModal(id);
      });
    });

    const checkAllEl = $(checkAllSelector);
    if (checkAllEl) checkAllEl.checked = false;
  }

  // ===============================
  // 검색, 등록, 수정, 삭제, 엑셀 등
  // (기존 기능 모두 그대로 유지, 주석 건드리지 않음)
  // ===============================

  const searchInputEl = $(searchInputSelector);
  const searchBtnEl = $(searchBtnSelector);
  if (searchBtnEl) searchBtnEl.addEventListener("click", () => {
    if (mode === "client") isFullDataLoaded = false;
    loadList(0);
  });
  if (searchInputEl) searchInputEl.addEventListener("keydown", e => {
    if (e.key === "Enter") searchBtnEl?.click();
  });

  $(addBtnSelector)?.addEventListener("click", () => $(modalId).style.display = "block");
  $(saveBtnSelector)?.addEventListener("click", async () => {
    const data = {};
    columns.forEach(col => { if ($(col.inputSelector)) data[col.key] = $(col.inputSelector).value; });
    try {
      const res = await fetch(apiUrl, fetchOptions("POST", data));
      const result = await res.json();
      alert(result.status === "success" ? "등록 완료" : "등록 실패");
      $(modalId).style.display = "none";
      if (mode === "client") isFullDataLoaded = false;
      await loadList(currentPage);
    } catch (err) { console.error(err); alert("등록 중 오류 발생"); }
  });

  async function openDetailModal(id) {
    try {
      const res = await fetch(`${apiUrl}/${id}`, fetchOptions("GET"));
      if (!res.ok) throw new Error("상세 조회 실패");
      const item = await res.json();
      Object.keys(detailFields).forEach(key => { const sel = detailFields[key]; if ($(sel)) $(sel).value = item[key] ?? ""; });
      $(detailModalId).style.display = "block";
    } catch (err) { console.error(err); alert("상세 조회 오류 발생"); }
  }

  $(updateBtnSelector)?.addEventListener("click", async () => {
    const id = $(detailFields.id).value;
    const data = {};
    Object.keys(detailFields).forEach(key => { if (key !== "id") data[key] = $(detailFields[key])?.value; });
    try {
      const res = await fetch(`${apiUrl}/${id}`, fetchOptions("PUT", data));
      const result = await res.json();
      alert(result.status === "updated" ? "수정 완료" : "수정 실패");
      $(detailModalId).style.display = "none";
      if (mode === "client") isFullDataLoaded = false;
      await loadList(currentPage);
    } catch (err) { console.error(err); alert("수정 오류 발생"); }
  });

  $(deleteSelectedBtnSelector)?.addEventListener("click", async () => {
    const checked = Array.from(document.querySelectorAll(`${tableBodySelector} input[type='checkbox']:checked`)).map(chk => parseInt(chk.value));
    if (checked.length === 0) return alert("삭제할 항목을 선택하세요.");
    if (!confirm(`${checked.length}건을 삭제하시겠습니까?`)) return;
    try {
      const res = await fetch(apiUrl, fetchOptions("DELETE", checked));
      const result = await res.json();
      alert(result.message || "삭제 완료");
      if (mode === "client") isFullDataLoaded = false;
      await loadList(currentPage);
    } catch (err) { console.error(err); alert("삭제 중 오류 발생"); }
  });

  $(excelBtnSelector)?.addEventListener("click", async () => {
    try {
      const search = $(searchInputSelector)?.value || "";
      const timestamp = new Date().getTime();
      const url = `${apiUrl}/excel?search=${encodeURIComponent(search)}&t=${timestamp}`;
      const token = localStorage.getItem("token");
      const headers = token ? { Authorization: "Bearer " + token } : {};
      const res = await fetch(url, { method: "GET", headers });
      if (!res.ok) throw new Error("엑셀 다운로드 실패");
      const disposition = res.headers.get("Content-Disposition");
      let filename = "리스트.xlsx";
      if (disposition) {
        const utf8 = disposition.match(/filename\*=UTF-8''(.+)/);
        const ascii = disposition.match(/filename="(.+)"/);
        if (utf8) filename = decodeURIComponent(utf8[1]);
        else if (ascii) filename = ascii[1];
      }
      const blob = await res.blob();
      const blobUrl = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = blobUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(blobUrl);
    } catch (err) { console.error(err); alert("엑셀 다운로드 오류"); }
  });

  const checkAllEl = $(checkAllSelector);
  if (checkAllEl) {
    checkAllEl.addEventListener("change", e => {
      const checked = e.target.checked;
      document.querySelectorAll(`${tableBodySelector} input[type='checkbox']`).forEach(chk => chk.checked = checked);
    });
  }

  document.addEventListener("change", e => {
    if (e.target.matches(`${tableBodySelector} input[type='checkbox']`)) {
      const all = document.querySelectorAll(`${tableBodySelector} input[type='checkbox']`);
      const checked = document.querySelectorAll(`${tableBodySelector} input[type='checkbox']:checked`);
      if (checkAllEl) checkAllEl.checked = all.length === checked.length;
    }
  });

  $$(closeBtnSelector).forEach(btn => {
    btn.addEventListener("click", e => {
      const targetId = e.target.closest("[data-close]")?.dataset.close;
      if (targetId) $(`#${targetId}`).style.display = "none";
    });
  });

  // ===============================
  // 초기 로드
  // ===============================
  loadList(0);

  // ===============================
  // 리사이즈 시 페이징 재렌더링
  // ===============================
  window.addEventListener("resize", () => {
    renderPagination(currentPage, totalPagesCache, paginationSelector, loadList, configGroupSize);
  });
}
