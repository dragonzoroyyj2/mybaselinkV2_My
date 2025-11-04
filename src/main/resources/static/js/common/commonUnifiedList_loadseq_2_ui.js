/* ===============================================================
   ✅ commonUnifiedList_ui.js (v1.9 - 2025.11 완전 분리판)
   ---------------------------------------------------------------
   - UnifiedList UI 관련 기능 전용 모듈
   - renderTable / _renderClientData / _renderPagination / toggleButtons
   - 로딩 오버레이, 모달 로딩, 시각적 처리 포함
================================================================ */

UnifiedList.prototype._renderClientData = function () {
  const tbody = document.querySelector(this.config.tableBodySelector);
  if (!tbody) return;
  const list = Array.isArray(this._clientData) ? this._clientData : [];

  // ✅ pagination: false → 전체 표시
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
};

/* ----------------------------------------------------------
   🧾 테이블 렌더링
   ----------------------------------------------------------
   - 데이터 배열을 기반으로 <tr> 동적 생성
   - 컬럼 정의(config.columns)에 따라 렌더
   - isDetailLink: 클릭 시 상세 모달
---------------------------------------------------------- */
UnifiedList.prototype.renderTable = function (list) {
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
};

/* ----------------------------------------------------------
   📑 페이징 렌더링
   ----------------------------------------------------------
   - 외부 commonPagination_op.js 의 renderPagination() 호출
---------------------------------------------------------- */
UnifiedList.prototype._renderPagination = function (totalPages) {
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
};

/* ----------------------------------------------------------
   🎛️ 버튼 표시 제어
   ----------------------------------------------------------
   - config.buttons 객체 기반으로 표시/숨김 처리
---------------------------------------------------------- */
UnifiedList.prototype.toggleButtons = function () {
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
};

/* ----------------------------------------------------------
   🌀 로딩 오버레이 제어
---------------------------------------------------------- */
UnifiedList.prototype._hideGlobalOverlay = function () {
  document.querySelectorAll(".global-loading-overlay").forEach((ov) => {
    ov.style.display = "none";
    ov.style.pointerEvents = "none";
  });
};

/* ----------------------------------------------------------
   🧩 모달 로딩 제어
---------------------------------------------------------- */
UnifiedList.prototype._showModalLoading = function (modal) {
  let overlay = modal.querySelector(".modal-loading");
  if (!overlay) {
    overlay = document.createElement("div");
    overlay.className = "modal-loading";
    overlay.innerHTML = `<div class="spinner"></div>`;
    modal.appendChild(overlay);
  }
  overlay.style.display = "flex";
};

UnifiedList.prototype._hideModalLoading = function (modal) {
  const overlay = modal.querySelector(".modal-loading");
  if (overlay) overlay.style.display = "none";
};
