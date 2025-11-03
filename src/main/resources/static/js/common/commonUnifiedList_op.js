/* ===============================================================
   ✅ commonUnifiedList_op.js (v1.4 - 2025.11 완전 통합 안정판)
   ---------------------------------------------------------------
   - 모든 HTML 입력태그 자동 매핑 (input, select, textarea 등)
   - 팝업 닫기/저장/수정 정상
   - 기존 기능 완전 유지 (v1.3 기반)
   - ✅ 화면 리사이즈 시 페이징 자동 재조정 (원본 구조 복원)
   - ✅ 검색어 유지 기능 추가
   - ✅ 체크박스 컬럼 자동 제어 추가
   - ✅ /list → /excel 자동 변환 (HTML=Controller명 통합 구조 대응)
================================================================ */

function initUnifiedList(config) {
  if (window.unifiedListInstance) {
    window.unifiedListInstance.reinit(config);
    return window.unifiedListInstance;
  }
  const inst = new UnifiedList(config);
  window.unifiedListInstance = inst;
  return inst;
}

class UnifiedList {
  constructor(config) {
    this.reinit(config);
  }

  reinit(config) {
    this.config = config;
    this.pageSize = config.pageSize || 10;
    this.pageGroupSize = config.pageGroupSize || 5;
    this.currentPage = 0;
    this._clientData = null;
    this.lastSearch = ""; // ✅ 검색어 기억
    this.csrfToken = document.querySelector("meta[name='_csrf']")?.content;
    this.csrfHeader = document.querySelector("meta[name='_csrf_header']")?.content;

    this._bindGlobalEvents();
    this.toggleButtons();
    this.loadList(0);
  }

  /* ----------------------------------------------------------
     🖱️ 전역 이벤트 (1회만)
  ---------------------------------------------------------- */
  _bindGlobalEvents() {
    if (this._bound) return;
    document.body.addEventListener("click", (e) => this._onClick(e));
    document.body.addEventListener("keydown", (e) => this._onKey(e));
    this._bound = true;
  }

  /* ----------------------------------------------------------
     📥 리스트 조회
  ---------------------------------------------------------- */
  async loadList(page = 0, _env = "web", search = "") {
    // ✅ 검색어 유지
    if (!search && this.lastSearch) search = this.lastSearch;
    else if (search) this.lastSearch = search;

    this.currentPage = page;
    const tbody = document.querySelector(this.config.tableBodySelector);
    if (!tbody) return;

    // ✅ 전역 오버레이 (화면 중앙 기준)
    let overlay = document.querySelector(".global-loading-overlay");
    if (!overlay) {
      overlay = document.createElement("div");
      overlay.className = "global-loading-overlay";
      overlay.innerHTML = `
        <div class="global-spinner-wrap">
          <div class="spinner"></div>
        </div>`;
      document.body.appendChild(overlay);
    }

    overlay.style.display = "flex";
    const startTime = Date.now();

    try {
      const url =
        `${this.config.apiUrl}?page=${page}&size=${this.pageSize}` +
        `&mode=${this.config.mode}&pagination=${this.config.pagination}` +
        `&search=${encodeURIComponent(search)}`;
      const res = await fetch(url, this._opts("GET"));
      if (!res.ok) throw new Error("조회 실패");
      const data = await res.json();

      const list = Array.isArray(data.content) ? data.content : [];
      if (this.config.mode === "client") {
        this._clientData = list;
        this._renderClientData();
      } else {
        this.renderTable(list);
        this._renderPagination(data.totalPages || 1);
      }

      const totalEl = document.getElementById("totalCount");
      if (totalEl)
        totalEl.textContent = `총 ${data.totalElements ?? list.length}건`;
    } catch (err) {
      console.error(err);
      tbody.innerHTML = `<tr><td colspan="100%">데이터 조회 오류</td></tr>`;
    } finally {
      const elapsed = Date.now() - startTime;
      const delay = Math.max(0, 200 - elapsed);
      setTimeout(() => {
        overlay.style.display = "none";
      }, delay);
    }
  }

  _renderClientData() {
    const tbody = document.querySelector(this.config.tableBodySelector);
    if (!tbody) return;
    const list = Array.isArray(this._clientData) ? this._clientData : [];

    if (this.config.pagination === false) {
      this.renderTable(list);
      const pg = document.querySelector(this.config.paginationSelector);
      if (pg) pg.innerHTML = "";
    } else {
      const start = this.currentPage * this.pageSize;
      const end = start + this.pageSize;
      this.renderTable(list.slice(start, end));
      this._renderPagination(Math.ceil(list.length / this.pageSize));
    }
  }

  renderTable(list) {
    const tbody = document.querySelector(this.config.tableBodySelector);
    tbody.innerHTML = "";

    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="${(this.config.columns?.length || 0) + 1}">데이터 없음</td></tr>`;
      return;
    }

    const hasCheckbox = (this.config.columns || []).some(c => c.checkbox === true); // ✅ 체크박스 자동 제어

    list.forEach((row) => {
      const tr = document.createElement("tr");
      tr.dataset.id = row.id;
      if (this.config.enableRowClickDetail)
        tr.classList.add("clickable-row");

      if (hasCheckbox) {
        const chk = document.createElement("td");
        chk.innerHTML = `<input type="checkbox" class="row-checkbox" data-id="${row.id}">`;
        tr.appendChild(chk);
      }

      (this.config.columns || []).forEach((col) => {
        if (col.checkbox === true) return;
        const td = document.createElement("td");
        const val = row[col.key] ?? "";
        if (col.isDetailLink)
          td.innerHTML = `<a href="#" class="detail-link" data-id="${row.id}" style="color:#2563eb;text-decoration:none;">${val}</a>`;
        else td.textContent = val;
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });

    const checkAll = document.querySelector(this.config.checkAllSelector);
    if (checkAll) checkAll.checked = false;
  }

  _renderPagination(totalPages) {
    this._lastTotalPages = totalPages;
    const el = document.querySelector(this.config.paginationSelector);
    if (!el) return;
    if (this.config.pagination === false) {
      el.innerHTML = "";
      return;
    }
    if (typeof renderPagination === "function") {
      renderPagination(
        this.currentPage,
        totalPages,
        this.config.paginationSelector,
        this.loadList.bind(this),
        this.pageGroupSize
      );
    } else el.innerHTML = "";
  }

  toggleButtons() {
    const map = {
      search: this.config.searchBtnSelector,
      add: this.config.addBtnSelector,
      deleteSelected: this.config.deleteSelectedBtnSelector,
      excel: this.config.excelBtnSelector,
    };
    const cfg = this.config.buttons || {};
    Object.keys(map).forEach((k) => {
      const el = document.querySelector(map[k]);
      if (el) el.style.display = cfg[k] === false ? "none" : "";
    });
  }

  /* ----------------------------------------------------------
     🔍 검색
  ---------------------------------------------------------- */
  _onKey(e) {
    if (e.key === "Enter" && e.target.matches(this.config.searchInputSelector)) {
      e.preventDefault();
      const s =
        document.querySelector(this.config.searchInputSelector)?.value || "";
      this._clientData = null;
      this.lastSearch = s; // ✅ 입력값 유지
      this.loadList(0, "web", s);
    }
  }

  /* ----------------------------------------------------------
     🖱️ 클릭
  ---------------------------------------------------------- */
  _onClick(e) {
    const t = e.target,
      q = (sel) => t.closest(sel);

    if (q(this.config.searchBtnSelector)) {
      e.preventDefault();
      const s =
        document.querySelector(this.config.searchInputSelector)?.value || "";
      this._clientData = null;
      this.lastSearch = s; // ✅ 검색 유지
      this.loadList(0, "web", s);
      return;
    }

    if (q(this.config.addBtnSelector)) {
      e.preventDefault();
      this.openAddModal();
      return;
    }

    if (t.matches(this.config.checkAllSelector)) {
      const checked = t.checked;
      document
        .querySelectorAll(`${this.config.tableBodySelector} .row-checkbox`)
        .forEach((cb) => (cb.checked = checked));
      return;
    }

    if (q(this.config.deleteSelectedBtnSelector)) {
      e.preventDefault();
      this.deleteSelected();
      return;
    }

    if (q(this.config.excelBtnSelector)) {
      e.preventDefault();
      this.downloadExcel();
      return;
    }

    if (t.classList.contains("detail-link")) {
      e.preventDefault();
      this.openDetailModal(t.dataset.id);
      return;
    }

    const row = t.closest(".clickable-row");
    if (this.config.enableRowClickDetail && row && !t.closest(".row-checkbox")) {
      this.openDetailModal(row.dataset.id);
      return;
    }

    if (t.matches("[data-close]")) {
      const id = t.dataset.close;
      this.closeModal(`#${id}`);
    }
  }

  /* ----------------------------------------------------------
     🧩 등록 모달
  ---------------------------------------------------------- */
  openAddModal() {
    this.closeAllModals(true);
    const modal = document.querySelector(this.config.modalId);
    if (!modal) return;
    modal.style.display = "flex";
    modal.classList.add("active");
    document.body.classList.add("modal-open");

    const saveBtn = modal.querySelector("#saveBtn");
    if (saveBtn) {
      saveBtn.replaceWith(saveBtn.cloneNode(true));
      modal.querySelector("#saveBtn").addEventListener("click", () =>
        this.saveData()
      );
    }
  }

  /* ----------------------------------------------------------
     🧩 상세 모달 (모든 HTML 태그 지원)
  ---------------------------------------------------------- */
  async openDetailModal(id) {
    this.closeAllModals(true);
    const modal = document.querySelector(this.config.detailModalId);
    if (!modal) return;

    modal.style.display = "flex";
    modal.classList.add("active");
    document.body.classList.add("modal-open");
    this._showModalLoading(modal);

    try {
      const res = await fetch(`${this.config.apiUrl}/${id}`, this._opts("GET"));
      if (!res.ok) throw new Error("상세 조회 실패");
      const data = await res.json();

      // ✅ HTML 모든 태그 자동 매핑
      Object.entries(data).forEach(([k, v]) => {
        const Cap = k.charAt(0).toUpperCase() + k.slice(1);
        const elements = modal.querySelectorAll(
          `#detail${Cap}, [data-field='${k}'], [name='${k}']`
        );
        if (!elements.length) return;

        elements.forEach((el) => {
          const tag = el.tagName.toLowerCase();
          const type = el.type ? el.type.toLowerCase() : "";

          if (tag === "input") {
            switch (type) {
              case "checkbox":
                if (
                  el.name &&
                  modal.querySelectorAll(`input[name='${el.name}']`).length > 1
                ) {
                  el.checked = Array.isArray(v)
                    ? v.includes(el.value)
                    : v === el.value;
                } else {
                  el.checked =
                    v === true ||
                    v === "true" ||
                    v === "Y" ||
                    v === "1" ||
                    v === el.value;
                }
                break;
              case "radio":
                if (el.value == v || String(el.value) === String(v))
                  el.checked = true;
                break;
              case "file":
                break;
              default:
                el.value = v ?? "";
            }
          } else if (tag === "select") {
            if (Array.isArray(v)) {
              for (const opt of el.options)
                opt.selected = v.includes(opt.value);
            } else el.value = v ?? "";
          } else if (tag === "textarea") {
            el.value = v ?? "";
          } else if (tag === "button") {
            el.textContent = v ?? "";
          } else {
            if ("value" in el) el.value = v ?? "";
            else el.textContent = v ?? "";
          }
        });
      });

      const updateBtn = modal.querySelector("#updateBtn");
      if (updateBtn) {
        updateBtn.replaceWith(updateBtn.cloneNode(true));
        modal
          .querySelector("#updateBtn")
          .addEventListener("click", () => this.updateData(id));
      }
    } catch (e) {
      console.error(e);
      notify?.("error", "상세 조회 실패");
    } finally {
      this._hideModalLoading(modal);
    }
  }

  /* ----------------------------------------------------------
     🧩 모달 닫기
  ---------------------------------------------------------- */
  closeAllModals(keepOne = false) {
    document.querySelectorAll(".modal").forEach((m) => {
      m.classList.remove("active");
      m.style.display = "none";
    });
    if (!keepOne) document.body.classList.remove("modal-open");
  }

  closeModal(sel) {
    const el = document.querySelector(sel);
    if (el) {
      el.classList.remove("active");
      el.style.display = "none";
    }
    document.body.classList.remove("modal-open");
  }

  /* ----------------------------------------------------------
     ➕ 등록
  ---------------------------------------------------------- */
  async saveData() {
    if (!this._validateRequired(this.config.modalId)) return;
    const modal = document.querySelector(this.config.modalId);
    const data = {};
    modal.querySelectorAll("input,textarea,select").forEach((el) => {
      const key = el.dataset.field || el.name || el.id || "";
      if (key)
        data[key.replace(/^detail/, "").replace(/Input$/, "")] = el.value;
    });

    CommonLoading?.show?.();
    try {
      const res = await fetch(this.config.apiUrl, this._opts("POST", data));
      if (!res.ok) throw new Error("등록 실패");
      notify?.("success", "등록 완료");
      this.closeAllModals();
      this._clientData = null;
      await this.loadList(this.currentPage, "web", this.lastSearch);
    } catch (e) {
      console.error(e);
      notify?.("error", "등록 실패");
    } finally {
      CommonLoading?.hide?.();
    }
  }

  /* ----------------------------------------------------------
     ✏️ 수정
  ---------------------------------------------------------- */
  async updateData(id) {
    if (!this._validateRequired(this.config.detailModalId)) return;
    const modal = document.querySelector(this.config.detailModalId);
    const data = {};
    modal.querySelectorAll("input,textarea,select").forEach((el) => {
      const raw = el.dataset.field || el.name || el.id || "";
      if (!raw) return;
      let key = raw.replace(/^detail/, "");
      key = key.charAt(0).toLowerCase() + key.slice(1);
      key = key.replace(/Input$/, "");
      data[key] = el.value;
    });

    CommonLoading?.show?.();
    try {
      const res = await fetch(
        `${this.config.apiUrl}/${id}`,
        this._opts("PUT", data)
      );
      if (!res.ok) throw new Error("수정 실패");
      notify?.("success", "수정 완료");
      this.closeAllModals();
      this._clientData = null;
      await this.loadList(this.currentPage, "web", this.lastSearch);
    } catch (e) {
      console.error(e);
      notify?.("error", "수정 실패");
    } finally {
      CommonLoading?.hide?.();
    }
  }

  /* ----------------------------------------------------------
     🗑️ 삭제
  ---------------------------------------------------------- */
  async deleteSelected() {
    const ids = Array.from(
      document.querySelectorAll(
        `${this.config.tableBodySelector} .row-checkbox:checked`
      )
    ).map((cb) => parseInt(cb.dataset.id));
    if (!ids.length)
      return notify?.("warning", "삭제할 항목을 선택하세요");
    if (!confirm(`${ids.length}개 항목을 삭제하시겠습니까?`)) return;

    CommonLoading?.show?.();
    try {
      const res = await fetch(this.config.apiUrl, this._opts("DELETE", ids));
      if (!res.ok) throw new Error("삭제 실패");
      notify?.("success", "삭제 완료");
      this._clientData = null;
      await this.loadList(this.currentPage, "web", this.lastSearch);
    } catch (e) {
      console.error(e);
      notify?.("error", "삭제 실패");
    } finally {
      CommonLoading?.hide?.();
    }
  }

  /* ----------------------------------------------------------
     📊 엑셀 다운로드 (테이블/화면 중앙 스피너 표시)
     ✅ /list 자동 제거 → /excel 로 변환 (화면명=컨트롤러명 대응)
  ---------------------------------------------------------- */
  async downloadExcel() {
    const csrfToken = this.csrfToken;
    const csrfHeader = this.csrfHeader;
    const search =
      document.querySelector(this.config.searchInputSelector)?.value ||
      this.lastSearch || "";

    // ✅ /list 로 끝나는 경우 자동 제거 (컨트롤러 구조 일치)
    const baseUrl = this.config.apiUrl.replace(/\/list$/, "");
    const url = `${baseUrl}/excel?search=${encodeURIComponent(search)}&t=${Date.now()}`;

    let overlay = document.querySelector(".global-loading-overlay");
    if (!overlay) {
      overlay = document.createElement("div");
      overlay.className = "global-loading-overlay";
      overlay.innerHTML = `
        <div class="global-spinner-wrap">
          <div class="spinner"></div>
        </div>`;
      document.body.appendChild(overlay);
    }
    overlay.style.display = "flex";

    try {
      const headers = {};
      if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
      const res = await fetch(url, { method: "GET", headers });
      if (!res.ok) throw new Error("엑셀 다운로드 실패");

      const disposition = res.headers.get("Content-Disposition");
      let filename = this.config.excelFileName || "리스트.xlsx";
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
      notify?.("success", `${filename} 다운로드 완료`);
    } catch (err) {
      console.error(err);
      alert("엑셀 다운로드 오류");
    } finally {
      overlay.style.display = "none";
    }
  }

  /* ----------------------------------------------------------
     ✅ 필수값 검증
  ---------------------------------------------------------- */
  _validateRequired(containerSel) {
    const container = document.querySelector(containerSel);
    if (!container) return true;
    const requiredCols = (this.config.columns || []).filter((c) => c.required);
    for (const col of requiredCols) {
      const sel = `#${col.key}, #${col.key}Input, #detail${col.key
        .charAt(0)
        .toUpperCase()}${col.key.slice(1)}`;
      const el = container.querySelector(sel);
      const val = el ? String(el.value ?? "").trim() : "";
      if (!val) {
        el?.classList.add("input-error");
        el?.focus();
        notify?.("warning", `'${col.label}'은(는) 필수 입력 항목입니다.`);
        return false;
      }
      el.classList.remove("input-error");
    }
    return true;
  }

  /* ----------------------------------------------------------
     ⏳ 모달 로딩 오버레이
  ---------------------------------------------------------- */
  _showModalLoading(modal) {
    let overlay = modal.querySelector(".modal-loading");
    if (!overlay) {
      overlay = document.createElement("div");
      overlay.className = "modal-loading";
      overlay.innerHTML = `<div class="spinner"></div>`;
      modal.appendChild(overlay);
    }
    overlay.style.display = "flex";
  }

  _hideModalLoading(modal) {
    const overlay = modal.querySelector(".modal-loading");
    if (overlay) overlay.style.display = "none";
  }

  /* ----------------------------------------------------------
     ⚙️ 요청 옵션
  ---------------------------------------------------------- */
  _opts(method, body = null) {
    const headers = { "Content-Type": "application/json" };
    if (this.csrfHeader && this.csrfToken)
      headers[this.csrfHeader] = this.csrfToken;
    return { method, headers, body: body ? JSON.stringify(body) : undefined };
  }
}

/* ==========================================================
   ✅ 반응형 페이징 (원본 구조 복원)
========================================================== */
function getPageGroupSize() {
  const w = window.innerWidth;
  if (w < 480) return 3;
  if (w < 768) return 5;
  if (w < 1024) return 10;
  return 20;
}

let pageGroupSize = getPageGroupSize();

window.addEventListener("resize", () => {
  const newSize = getPageGroupSize();
  if (newSize !== pageGroupSize) {
    pageGroupSize = newSize;
    const inst = window.unifiedListInstance;
    if (!inst) return;

    const totalPages =
      (inst._lastTotalPages || inst._clientData?.length / inst.pageSize) || 1;

    renderPagination(
      inst.currentPage,
      Math.ceil(totalPages),
      inst.config.paginationSelector,
      inst.loadList.bind(inst),
      pageGroupSize
    );
  }
});
