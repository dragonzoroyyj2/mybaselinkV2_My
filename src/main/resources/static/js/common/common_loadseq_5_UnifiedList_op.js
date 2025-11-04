/* ===============================================================
   ✅ commonUnifiedList_op.js (v1.0)
   ---------------------------------------------------------------
   - 모든 HTML 입력태그 자동 매핑 (input, select, textarea 등)
   - 팝업 닫기/저장/수정/엑셀/삭제 후에도 클릭 정상 ✅
   - overlay 중복 및 pointer-events 차단 완전 제거
   - 기존 기능 완전 유지 (v1.7 기반)
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
     🖱️ 전역 이벤트 (항상 유지)
  ---------------------------------------------------------- */
  _bindGlobalEvents() {
    document.body.removeEventListener("click", this._clickHandler);
    document.body.removeEventListener("keydown", this._keyHandler);
    this._clickHandler = (e) => this._onClick(e);
    this._keyHandler = (e) => this._onKey(e);
    document.body.addEventListener("click", this._clickHandler);
    document.body.addEventListener("keydown", this._keyHandler);
  }

  /* ----------------------------------------------------------
     📥 리스트 조회
  ---------------------------------------------------------- */
  /* ----------------------------------------------------------
     📥 리스트 조회 (v1.9 수정판)
     ----------------------------------------------------------
     - ✅ mode: "client" 일 경우 최초 1회만 서버 요청 (캐시 후 로컬 페이징)
     - ✅ mode: "server" 일 경우 매 페이지마다 서버 요청
     - ✅ overlay 중복 방지 및 pointer-events 해제 포함
  ---------------------------------------------------------- */
  async loadList(page = 0, _env = "web", search = "") {
    // 🔹 검색어 유지 로직
    if (!search && this.lastSearch) search = this.lastSearch;
    else if (search) this.lastSearch = search;

    // 🔹 현재 페이지 기록
    this.currentPage = page;

    const tbody = document.querySelector(this.config.tableBodySelector);
    if (!tbody) return;

    // ✅ client 모드일 때 이미 전체 데이터가 있다면 서버통신 생략
    if (this.config.mode === "client" && this._clientData && _env !== "force") {
      this._renderClientData();
      return;
    }

    // ✅ 로딩 오버레이 생성 (없을 때만)
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
    overlay.style.pointerEvents = "auto";

    const startTime = Date.now();

    try {
      // ✅ 서버 호출은 server 모드이거나 client 모드의 최초 1회만 수행
      const url =
        `${this.config.apiUrl}?page=${page}&size=${this.pageSize}` +
        `&mode=${this.config.mode}&pagination=${this.config.pagination}` +
        `&search=${encodeURIComponent(search)}`;

      const res = await fetch(url, this._opts("GET"));
      if (!res.ok) throw new Error("조회 실패");
      const data = await res.json();

      const list = Array.isArray(data.content) ? data.content : [];

      // ✅ client 모드 → 데이터를 메모리에 저장 후 로컬 페이징
      if (this.config.mode === "client") {
        this._clientData = list;
        this._renderClientData();
      }
      // ✅ server 모드 → 서버 데이터 그대로 렌더링
      else {
        this.renderTable(list);
        this._renderPagination(data.totalPages || 1);
      }

      // ✅ 총 건수 표시
      const totalEl = document.getElementById("totalCount");
      if (totalEl)
        totalEl.textContent = `총 ${data.totalElements ?? list.length}건`;
    } catch (err) {
      console.error(err);
      tbody.innerHTML = `<tr><td colspan="100%">데이터 조회 오류</td></tr>`;
    } finally {
      const elapsed = Date.now() - startTime;
      const delay = Math.max(0, 100 - elapsed);
      setTimeout(() => {
        overlay.style.display = "none";
        overlay.style.pointerEvents = "none";
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

    const hasCheckbox = (this.config.columns || []).some(c => c.checkbox === true);

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

  _onKey(e) {
    if (e.key === "Enter" && e.target.matches(this.config.searchInputSelector)) {
      e.preventDefault();
      const s =
        document.querySelector(this.config.searchInputSelector)?.value || "";
      this._clientData = null;
      this.lastSearch = s;
      this.loadList(0, "web", s);
    }
  }

  _onClick(e) {
    const t = e.target,
      q = (sel) => t.closest(sel);

    if (q(this.config.searchBtnSelector)) {
      e.preventDefault();
      const s =
        document.querySelector(this.config.searchInputSelector)?.value || "";
      this._clientData = null;
      this.lastSearch = s;
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

  openAddModal() {
    this.closeAllModals(true);
    const modal = document.querySelector(this.config.modalId);
    if (!modal) return;

    modal.style.display = "flex";
    modal.classList.add("active");
    document.body.classList.add("modal-open");

    const saveBtn = modal.querySelector("#saveBtn");
    if (saveBtn && !saveBtn._hasHandler) {
      saveBtn.addEventListener("click", () => this.saveData());
      saveBtn._hasHandler = true;
    }
  }

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
      if (updateBtn && !updateBtn._hasHandler) {
        updateBtn.addEventListener("click", () => this.updateData(id));
        updateBtn._hasHandler = true;
      }
    } catch (e) {
      console.error(e);
      Toast?.show?.("상세 조회 실패", "error");
    } finally {
      this._hideModalLoading(modal);
    }
  }

  closeAllModals(keepOne = false) {
    document.querySelectorAll(".modal").forEach((m) => {
      m.classList.remove("active");
      m.style.display = "none";
    });
    if (!keepOne) document.body.classList.remove("modal-open");
    this._hideGlobalOverlay();
  }

  closeModal(sel) {
    const el = document.querySelector(sel);
    if (el) {
      el.classList.remove("active");
      el.style.display = "none";
    }
    document.body.classList.remove("modal-open");
    this._hideGlobalOverlay();
  }

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
      Toast?.show?.("등록 완료", "success");
      this.closeAllModals();
      this._clientData = null;
      await this.loadList(this.currentPage, "web", this.lastSearch);
    } catch (e) {
      console.error(e);
      Toast?.show?.("등록 실패", "error");
    } finally {
      CommonLoading?.hide?.();
      this._hideGlobalOverlay();
      this._bindGlobalEvents();
      document.body.classList.remove("modal-open");
    }
  }

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
      Toast?.show?.("수정 완료", "success");
      this.closeAllModals();
      this._clientData = null;
      await this.loadList(this.currentPage, "web", this.lastSearch);
    } catch (e) {
      console.error(e);
      Toast?.show?.("수정 실패", "error");
    } finally {
      CommonLoading?.hide?.();
      this._hideGlobalOverlay();
      this._bindGlobalEvents();
      document.body.classList.remove("modal-open");
    }
  }

  async deleteSelected() {
    const ids = Array.from(
      document.querySelectorAll(
        `${this.config.tableBodySelector} .row-checkbox:checked`
      )
    ).map((cb) => parseInt(cb.dataset.id));
    if (!ids.length)
      return Toast?.show?.("삭제할 항목을 선택하세요", "warning");
    if (!confirm(`${ids.length}개 항목을 삭제하시겠습니까?`)) return;

    CommonLoading?.show?.();
    try {
      const res = await fetch(this.config.apiUrl, this._opts("DELETE", ids));
      if (!res.ok) throw new Error("삭제 실패");
      Toast?.show?.("삭제 완료", "success");
      this._clientData = null;
      await this.loadList(this.currentPage, "web", this.lastSearch);
    } catch (e) {
      console.error(e);
      Toast?.show?.("삭제 실패", "error");
    } finally {
      CommonLoading?.hide?.();
      this._hideGlobalOverlay();
      this._bindGlobalEvents();
      document.body.classList.remove("modal-open");
    }
  }

  async downloadExcel() {
    const csrfToken = this.csrfToken;
    const csrfHeader = this.csrfHeader;
    const search =
      document.querySelector(this.config.searchInputSelector)?.value ||
      this.lastSearch || "";

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
    overlay.style.pointerEvents = "auto";

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
      Toast?.show?.(`${filename} 다운로드 완료`, "success");
    } catch (err) {
      console.error(err);
      alert("엑셀 다운로드 오류");
    } finally {
      this._hideGlobalOverlay();
      this._bindGlobalEvents();
      document.body.classList.remove("modal-open");
    }
  }

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
        Toast?.show?.(`'${col.label}'은(는) 필수 입력 항목입니다.`, "warning");
        return false;
      }
      el.classList.remove("input-error");
    }
    return true;
  }

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

  _hideGlobalOverlay() {
    document.querySelectorAll(".global-loading-overlay").forEach((ov) => {
      ov.style.display = "none";
      ov.style.pointerEvents = "none";
    });
  }

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
