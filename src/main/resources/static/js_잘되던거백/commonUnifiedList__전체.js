/**
 * 🧩 commonUnifiedList.js (완전 안정화 버전)
 * --------------------------------------------------------
 * ✅ 공용 리스트/CRUD/엑셀 + 반응형 테이블 + 페이징 자동조정
 * --------------------------------------------------------
 * 기능 개선:
 *   - currentPage 항상 동기화
 *   - 상세 모달 모든 필드 매핑
 *   - 모달 닫기 안전 처리
 *   - 페이징 버튼 반응형 조정
 *   - 체크박스 상태 유지
 *   - 검색 엔터 이벤트 안정화
 */

function initUnifiedList(config) {
  const {
    mode,
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
  let groupSize = configGroupSize || 5;
  let totalPagesCache = 0;

  const $ = sel => document.querySelector(sel);
  const $$ = sel => document.querySelectorAll(sel);

  // CSRF & JWT
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

  /** =====================
   * 📋 리스트 로드
   * ===================== */
  async function loadList(page = 0) {
    currentPage = page; // ✅ 항상 현재 페이지 동기화
    const search = $(searchInputSelector)?.value || "";
    const url = `${apiUrl}?page=${page}&size=${pageSize}&search=${encodeURIComponent(search)}`;

    try {
      const res = await fetch(url, fetchOptions("GET"));
      if (res.status === 401) {
        alert("세션 만료. 다시 로그인하세요.");
        localStorage.clear();
        window.location.href = "/login";
        return;
      }
      if (!res.ok) throw new Error("데이터 조회 실패");

      const data = await res.json();
      renderTable(data.content || []);
      renderPagination(data.page, data.totalPages);

      // 총 건수 업데이트
      const totalCountEl = document.getElementById("totalCount");
      if (totalCountEl) totalCountEl.textContent = `총 ${data.totalElements ?? 0}건`;

      document.dispatchEvent(
        new CustomEvent("totalCountUpdated", { detail: { count: data.totalElements ?? 0 } })
      );

    } catch (err) {
      console.error(err);
      alert("데이터 조회 중 오류 발생");
    }
  }

  /** =====================
   * 🧾 테이블 렌더링
   * ===================== */
  function renderTable(list) {
    const tbody = $(tableBodySelector);
    if (!tbody) return;
    tbody.innerHTML = "";

    if (list.length === 0) {
      tbody.innerHTML = `<tr><td colspan="${columns.length + 1}">데이터가 없습니다.</td></tr>`;
      return;
    }

    list.forEach((row, index) => {
      const tr = document.createElement("tr");

      // 체크박스 열
      const chkTd = document.createElement("td");
      chkTd.innerHTML = `<input type="checkbox" value="${row.id}">`;
      tr.appendChild(chkTd);

      // 컬럼 데이터
      columns.forEach(col => {
        const td = document.createElement("td");
        const val = row[col.key] ?? "";
        if (col.isDetailLink) {
          td.innerHTML = `<a href="#" data-id="${row.id}" class="detail-link">${val}</a>`;
        } else {
          td.textContent = val;
        }
        tr.appendChild(td);
      });

      tbody.appendChild(tr);
    });

    // 상세보기 링크 이벤트
    $$(".detail-link").forEach(a => {
      a.addEventListener("click", e => {
        e.preventDefault();
        openDetailModal(e.target.dataset.id);
      });
    });

    // 체크박스 전체 선택 상태 초기화
    const checkAllEl = $(checkAllSelector);
    if (checkAllEl) checkAllEl.checked = false;
  }

  /** =====================
   * 📌 페이징 렌더링
   * ===================== */
  function adjustGroupSize() {
    const tbody = $(tableBodySelector);
    if (!tbody) return;

    const containerWidth = tbody.offsetWidth;
    const approxBtnWidth = 36;
    let maxBtnPerRow = Math.floor(containerWidth / approxBtnWidth);

    if (window.innerWidth <= 768) maxBtnPerRow = Math.min(maxBtnPerRow, 5);
    groupSize = Math.min(configGroupSize || 5, maxBtnPerRow, totalPagesCache);
    if (groupSize < 1) groupSize = 1;
  }

  function renderPagination(page, totalPages) {
    totalPagesCache = totalPages;
    adjustGroupSize();

    const container = $(paginationSelector);
    if (!container) return;
    container.innerHTML = "";
    if (totalPages <= 0) return;

    const currentGroup = Math.floor(page / groupSize);
    const startPage = currentGroup * groupSize;
    const endPage = Math.min(startPage + groupSize, totalPages);

    const makeBtn = (text, disabled, click) => {
      const btn = document.createElement("button");
      btn.textContent = text;
      btn.disabled = disabled;
      if (!disabled) btn.addEventListener("click", click);
      container.appendChild(btn);
    };

    makeBtn("<<", page === 0, () => loadList(0));
    makeBtn("<", page === 0, () => loadList(page - 1));

    for (let i = startPage; i < endPage; i++) {
      const btn = document.createElement("button");
      btn.textContent = i + 1;
      if (i === page) btn.classList.add("active");
      btn.addEventListener("click", () => loadList(i));
      container.appendChild(btn);
    }

    makeBtn(">", page >= totalPages - 1, () => loadList(page + 1));
    makeBtn(">>", page >= totalPages - 1, () => loadList(totalPages - 1));

    container.style.maxWidth = $(tableBodySelector).offsetWidth + "px";
  }

  window.addEventListener("resize", () => {
    renderPagination(currentPage, totalPagesCache);
  });

  /** =====================
   * 🔍 검색
   * ===================== */
  const searchInputEl = $(searchInputSelector);
  const searchBtnEl = $(searchBtnSelector);
  if (searchBtnEl) searchBtnEl.addEventListener("click", () => loadList(0));
  if (searchInputEl) searchInputEl.addEventListener("keydown", e => {
    if (e.key === "Enter") searchBtnEl?.click();
  });

  /** =====================
   * ➕ 등록
   * ===================== */
  $(addBtnSelector)?.addEventListener("click", () => $(modalId).style.display = "block");
  $(saveBtnSelector)?.addEventListener("click", async () => {
    const data = {
      title: $("#titleInput").value,
      owner: $("#ownerInput").value
    };
    try {
      const res = await fetch(apiUrl, fetchOptions("POST", data));
      const result = await res.json();
      alert(result.status === "success" ? "등록 완료" : "등록 실패");
      $(modalId).style.display = "none";
      loadList(currentPage);
    } catch (err) {
      console.error(err);
      alert("등록 중 오류 발생");
    }
  });

  /** =====================
   * 🔎 상세 / 수정
   * ===================== */
  async function openDetailModal(id) {
    try {
      const res = await fetch(`${apiUrl}/${id}`, fetchOptions("GET"));
      if (!res.ok) throw new Error("상세 조회 실패");
      const item = await res.json();
      if (!item) return alert("데이터를 찾을 수 없습니다.");

      // ✅ 모든 필드 매핑
      Object.keys(detailFields).forEach(key => {
        const sel = detailFields[key];
        if ($(sel)) $(sel).value = item[key] ?? "";
      });

      $(detailModalId).style.display = "block";
    } catch (err) {
      console.error(err);
      alert("상세 조회 오류 발생");
    }
  }

  $(updateBtnSelector)?.addEventListener("click", async () => {
    const id = $(detailFields.id).value;
    const data = {};
    Object.keys(detailFields).forEach(key => {
      if (key !== "id") data[key] = $(detailFields[key])?.value;
    });

    try {
      const res = await fetch(`${apiUrl}/${id}`, fetchOptions("PUT", data));
      const result = await res.json();
      alert(result.status === "updated" ? "수정 완료" : "수정 실패");
      $(detailModalId).style.display = "none";
      loadList(currentPage);
    } catch (err) {
      console.error(err);
      alert("수정 오류 발생");
    }
  });

  /** =====================
   * ❌ 삭제
   * ===================== */
  $(deleteSelectedBtnSelector)?.addEventListener("click", async () => {
    const checked = Array.from(document.querySelectorAll(`${tableBodySelector} input[type='checkbox']:checked`))
      .map(chk => parseInt(chk.value));
    if (checked.length === 0) return alert("삭제할 항목을 선택하세요.");
    if (!confirm(`${checked.length}건을 삭제하시겠습니까?`)) return;

    try {
      const res = await fetch(apiUrl, fetchOptions("DELETE", checked));
      const result = await res.json();
      alert(result.message || "삭제 완료");
      loadList(currentPage);
    } catch (err) {
      console.error(err);
      alert("삭제 중 오류 발생");
    }
  });

  /** =====================
   * 📊 엑셀 다운로드
   * ===================== */
  $(excelBtnSelector)?.addEventListener("click", async () => {
    try {
      const search = $(searchInputSelector)?.value || "";
      const timestamp = new Date().getTime();
      const url = `${apiUrl}/excel?search=${encodeURIComponent(search)}&t=${timestamp}`;
      const token = localStorage.getItem("token");
      const headers = token ? { Authorization: "Bearer " + token } : {};
      const res = await fetch(url, { method: "GET", headers });
      if (res.status === 401) { alert("세션 만료"); localStorage.clear(); window.location.href="/login"; return; }
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
    } catch (err) {
      console.error("❌ Excel Download Error:", err);
      alert("엑셀 다운로드 오류");
    }
  });

  /** =====================
   * ✅ 체크박스 전체 선택/해제
   * ===================== */
  const checkAllEl = $(checkAllSelector);
  if (checkAllEl) {
    checkAllEl.addEventListener("change", e => {
      const checked = e.target.checked;
      document.querySelectorAll(`${tableBodySelector} input[type='checkbox']`)
        .forEach(chk => chk.checked = checked);
    });
  }
  document.addEventListener("change", e => {
    if (e.target.matches(`${tableBodySelector} input[type='checkbox']`)) {
      const all = document.querySelectorAll(`${tableBodySelector} input[type='checkbox']`);
      const checked = document.querySelectorAll(`${tableBodySelector} input[type='checkbox']:checked`);
      if (checkAllEl) checkAllEl.checked = all.length === checked.length;
    }
  });

  /** =====================
   * ❎ 모달 닫기 버튼
   * ===================== */
  $$(closeBtnSelector).forEach(btn => {
    btn.addEventListener("click", e => {
      const targetId = e.target.closest("[data-close]")?.dataset.close;
      if (targetId) $(`#${targetId}`).style.display = "none";
    });
  });

  /** =====================
   * 🚀 초기 로드
   * ===================== */
  loadList();
}
