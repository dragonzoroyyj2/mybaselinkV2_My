/* ===============================================
   commonUnifiedList_op.js — 안정화 완전체
   - 리스트 조회/검색/페이징
   - add/delete/excel/detail
   - columns[].required 검증 (id 패턴 자동 인식)
   - 모달 단일 오픈 보장 + 중앙 스피너
   =============================================== */

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
    this.csrfToken  = document.querySelector("meta[name='_csrf']")?.content;
    this.csrfHeader = document.querySelector("meta[name='_csrf_header']")?.content;
    this.spinner    = document.getElementById("loadingSpinner");

    this._bindOnce();
    this.toggleButtons();
    this.loadList(0);
  }

  _bindOnce() {
    if (this._bound) return;
    document.body.addEventListener("click", (e)=>this._onClick(e));
    document.body.addEventListener("keydown",(e)=>this._onKey(e));
    this._bound = true;
  }

  showSpinner(){ if(this.spinner) this.spinner.style.display="flex"; }
  hideSpinner(){ if(this.spinner) this.spinner.style.display="none"; }

  /* ------------ 조회 ------------- */
  async loadList(page=0, _env="web", search="") {
    this.currentPage = page;
    const tbody = document.querySelector(this.config.tableBodySelector);
    if (!tbody) return;

    // 테이블 중앙 스켈레톤/스피너
    const colSpan = (this.config.columns?.length || 0) + 1;
    tbody.innerHTML = `
      <tr>
        <td colspan="${colSpan}">
          <div class="table-loading">
            <div class="spinner"></div>
          </div>
        </td>
      </tr>`;
    this.showSpinner();

    try {
      const url = `${this.config.apiUrl}?page=${page}&size=${this.pageSize}&search=${encodeURIComponent(search)}`;
      const res = await fetch(url, this._opts("GET"));
      if (!res.ok) throw new Error("서버 조회 실패");
      const data = await res.json();

      const list = Array.isArray(data.content) ? data.content : [];
      // 최근 등록순(내림차순)
      list.sort((a,b)=>(b.id||0)-(a.id||0));

      this.renderTable(list);
      this._renderPagination(data.totalPages || 1);

      const totalEl = document.getElementById("totalCount");
      if (totalEl) totalEl.textContent = `총 ${data.totalElements ?? list.length}건`;

      notify?.("success","조회 완료", {dedupeKey:"list-load"});
    } catch(e) {
      console.error(e);
      notify?.("error","데이터 조회 실패",{dedupeKey:"list-load"});
      tbody.innerHTML = `<tr><td colspan="100%">데이터 조회 중 오류</td></tr>`;
    } finally {
      this.hideSpinner();
    }
  }

  renderTable(list){
    const tbody = document.querySelector(this.config.tableBodySelector);
    tbody.innerHTML = "";
    if (!list.length){
      tbody.innerHTML = `<tr><td colspan="${(this.config.columns?.length||0)+1}">데이터 없음</td></tr>`;
      return;
    }

    list.forEach(row=>{
      const tr = document.createElement("tr");
      tr.dataset.id = row.id;
      if (this.config.enableRowClickDetail) tr.classList.add("clickable-row");

      const chk = document.createElement("td");
      chk.innerHTML = `<input type="checkbox" class="row-checkbox" data-id="${row.id}">`;
      tr.appendChild(chk);

      this.config.columns.forEach(col=>{
        const td  = document.createElement("td");
        const val = row[col.key] ?? "";

        if (col.isDetailLink && this.config.enableDetailView && !this.config.enableRowClickDetail){
          td.innerHTML = `<a href="#" class="detail-link" data-id="${row.id}">${val}</a>`;
        } else {
          td.textContent = val;
        }
        tr.appendChild(td);
      });

      tbody.appendChild(tr);
    });

    const checkAll = document.querySelector(this.config.checkAllSelector);
    if (checkAll) checkAll.checked = false;
  }

  _renderPagination(totalPages){
    const el = document.querySelector(this.config.paginationSelector);
    if (!el) return;
    if (this.config.pagination !== false) {
      renderPagination(this.currentPage, totalPages, this.config.paginationSelector, this.loadList.bind(this), this.pageGroupSize);
    } else {
      el.innerHTML = "";
    }
  }

  /* ------------ 버튼/이벤트 ------------- */
  toggleButtons(){
    const map = {
      search: this.config.searchBtnSelector,
      add:    this.config.addBtnSelector,
      deleteSelected: this.config.deleteSelectedBtnSelector,
      excel:  this.config.excelBtnSelector
    };
    const cfg = this.config.buttons || {};
    Object.keys(map).forEach(k=>{
      const el = document.querySelector(map[k]);
      if (el) el.style.display = cfg[k] === false ? "none" : "";
    });
  }

  _onKey(e){
    if (e.key === "Enter" && e.target.matches(this.config.searchInputSelector)){
      e.preventDefault();
      const s = document.querySelector(this.config.searchInputSelector)?.value || "";
      this.loadList(0,"web",s);
    }
  }

  _onClick(e){
    const t = e.target;
    const q = (sel)=>t.closest(sel);

    if (q(this.config.searchBtnSelector)){
      e.preventDefault();
      const s = document.querySelector(this.config.searchInputSelector)?.value || "";
      this.loadList(0,"web",s);
      return;
    }

    if (q(this.config.addBtnSelector)){
      e.preventDefault();
      this.openAddModal();
      return;
    }

    if (t.matches(this.config.checkAllSelector)){
      const checked = t.checked;
      document.querySelectorAll(`${this.config.tableBodySelector} .row-checkbox`).forEach(cb=>cb.checked=checked);
      return;
    }

    if (q(this.config.deleteSelectedBtnSelector)){
      e.preventDefault();
      this.deleteSelected();
      return;
    }

    if (q(this.config.excelBtnSelector)){
      e.preventDefault();
      this.downloadExcel();
      return;
    }

    // 상세
    const detailLink   = q(".detail-link");
    const clickableRow = q(".clickable-row");

    if (this.config.enableDetailView){
      if (detailLink){
        e.preventDefault();
        const id = detailLink.dataset.id;
        this.openDetailModal(id);
        return;
      }
      if (this.config.enableRowClickDetail && clickableRow && !t.closest(".row-checkbox")){
        e.preventDefault();
        const id = clickableRow.dataset.id;
        this.openDetailModal(id);
        return;
      }
    }

    // 모달 닫기
    if (t.matches("[data-close]")){
      const id = t.dataset.close;
      this.closeModal(`#${id}`);
      return;
    }
  }

  /* ------------ 필수값 검증 ------------- */
  _requiredSelectorFor(key){
    // 기존 id 패턴/네이밍을 최대한 존중해서 모두 탐색
    const Cap = key.charAt(0).toUpperCase()+key.slice(1);
    return [
      `#${key}`,                 // title
      `#${key}Input`,            // titleInput
      `#detail${Cap}`,           // detailTitle
      `[name='${key}']`,         // name=title
      `[data-field='${key}']`    // 커스텀 data-field
    ].join(", ");
  }

  _validateRequired(containerSel){
    const container = document.querySelector(containerSel);
    if (!container) return true;

    const requiredCols = (this.config.columns || []).filter(c=>c.required);
    for (const col of requiredCols){
      const sel = this._requiredSelectorFor(col.key);
      const el  = container.querySelector(sel);
      if (!el || !String(el.value??"").trim()){
        el?.classList.add("input-error");
        el?.focus();
        notify?.("warning", `'${col.label}'은(는) 필수입니다.`, {dedupeKey:`req-${col.key}`});
        return false;
      }
      el.classList.remove("input-error");
    }
    return true;
  }

  /* ------------ CRUD ------------- */
  async saveData(){
    if (!this._validateRequired(this.config.modalId)) return;
    const modal = document.querySelector(this.config.modalId);
    const data  = {};
    modal.querySelectorAll("input,textarea,select").forEach(el=>{
      const key = el.dataset.field || el.name || el.id || "";
      if (key) data[key.replace(/^detail/,"").replace(/Input$/,"").replace(/^[A-Z]/,m=>m.toLowerCase())] = el.value;
    });

    this.showSpinner();
    try{
      const res = await fetch(this.config.apiUrl, this._opts("POST", data));
      if (!res.ok) throw new Error("등록 실패");
      notify?.("success","등록 완료",{dedupeKey:"save"});
      this.closeAllModals();
      await this.loadList(this.currentPage);
    }catch(e){
      console.error(e);
      notify?.("error","등록 중 오류",{dedupeKey:"save"});
    }finally{ this.hideSpinner(); }
  }

  async updateData(id){
    if (!this._validateRequired(this.config.detailModalId)) return;
    const modal = document.querySelector(this.config.detailModalId);
    const data  = {};
    modal.querySelectorAll("input,textarea,select").forEach(el=>{
      const raw = el.dataset.field || el.name || el.id || "";
      if (!raw) return;
      // detailTitle → title, detailOwner → owner, regDate → regDate
      let key = raw.replace(/^detail/,"");
      key = key.charAt(0).toLowerCase()+key.slice(1);
      key = key.replace(/Input$/,"");
      data[key] = el.value;
    });

    this.showSpinner();
    try{
      const res = await fetch(`${this.config.apiUrl}/${id}`, this._opts("PUT", data));
      if (!res.ok) throw new Error("수정 실패");
      notify?.("success","수정 완료",{dedupeKey:"update"});
      this.closeAllModals();
      await this.loadList(this.currentPage);
    }catch(e){
      console.error(e);
      notify?.("error","수정 중 오류",{dedupeKey:"update"});
    }finally{ this.hideSpinner(); }
  }

  async deleteSelected(){
    const ids = Array.from(document.querySelectorAll(`${this.config.tableBodySelector} .row-checkbox:checked`)).map(cb=>parseInt(cb.dataset.id));
    if (!ids.length) return notify?.("warning","삭제할 항목을 선택하세요.",{dedupeKey:"del-empty"});
    if (!confirm(`${ids.length}개 항목을 삭제하시겠습니까?`)) return;

    this.showSpinner();
    try{
      const res = await fetch(this.config.apiUrl, this._opts("DELETE", ids));
      if (!res.ok) throw new Error("삭제 실패");
      notify?.("success","삭제 완료",{dedupeKey:"del"});
      await this.loadList(this.currentPage);
    }catch(e){
      console.error(e);
      notify?.("error","삭제 중 오류",{dedupeKey:"del"});
    }finally{ this.hideSpinner(); }
  }

  async downloadExcel(){
    this.showSpinner();
    try{
      const s   = document.querySelector(this.config.searchInputSelector)?.value || "";
      const url = `${this.config.apiUrl}/excel?search=${encodeURIComponent(s)}&t=${Date.now()}`;
      const res = await fetch(url, this._opts("GET"));
      if (!res.ok) throw new Error("엑셀 실패");
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "리스트.xlsx";
      a.click();
      URL.revokeObjectURL(a.href);
      notify?.("success","엑셀 다운로드 완료",{dedupeKey:"excel"});
    }catch(e){
      console.error(e);
      notify?.("error","엑셀 다운로드 중 오류",{dedupeKey:"excel"});
    }finally{ this.hideSpinner(); }
  }

  /* ------------ 모달 ------------- */
  closeAllModals(){
    document.querySelectorAll(".modal").forEach(m=>m.style.display="none");
    document.body.classList.remove("modal-open");
  }

  openAddModal(){
    this.closeAllModals();
    const modal = document.querySelector(this.config.modalId);
    if (!modal) { notify?.("error","등록 모달을 찾을 수 없습니다."); return; }
    // 중앙 로딩으로 먼저 대체
    this._showModalLoading(modal);
    modal.style.display = "block";
    document.body.classList.add("modal-open");

    // 원래 폼 그대로 유지하고, 로딩 오버레이만 제거
    setTimeout(()=>{
      this._hideModalLoading(modal);
      // 저장 버튼 바인딩(중복 방지)
      const btn = modal.querySelector("#saveBtn");
      if (btn) { btn.onclick = ()=>this.saveData(); }
    }, 150);
  }

  async openDetailModal(id){
    this.closeAllModals();
    const modal = document.querySelector(this.config.detailModalId);
    if (!modal) { notify?.("error","상세 모달을 찾을 수 없습니다."); return; }

    // 로딩 오버레이
    modal.style.display = "block";
    document.body.classList.add("modal-open");
    this._showModalLoading(modal);

    try{
      const res = await fetch(`${this.config.apiUrl}/${id}`, this._opts("GET"));
      if (!res.ok) throw new Error("상세 조회 실패");
      const data = await res.json();

      // 데이터 바인딩 (detail* 우선, 없으면 data-field/name)
      Object.entries(data).forEach(([k,v])=>{
        const Cap = k.charAt(0).toUpperCase()+k.slice(1);
        const el = modal.querySelector(`#detail${Cap}, [data-field='${k}'], [name='${k}']`);
        if (el) el.value = v;
      });

      const updateBtn = modal.querySelector("#updateBtn");
      if (updateBtn) updateBtn.onclick = ()=>this.updateData(id);

      this._hideModalLoading(modal);
    }catch(e){
      console.error(e);
      this._hideModalLoading(modal);
      notify?.("error","상세 정보를 불러오는 중 오류",{dedupeKey:"detail"});
    }
  }

  closeModal(sel){
    const el = document.querySelector(sel);
    if (el) el.style.display = "none";
    document.body.classList.remove("modal-open");
  }

  _showModalLoading(modal){
    let overlay = modal.querySelector(".modal-loading");
    if (!overlay){
      overlay = document.createElement("div");
      overlay.className = "modal-loading";
      overlay.innerHTML = `<div class="spinner"></div>`;
      modal.appendChild(overlay);
    }
    overlay.style.display = "flex";
  }
  _hideModalLoading(modal){
    const overlay = modal.querySelector(".modal-loading");
    if (overlay) overlay.style.display = "none";
  }

  /* ------------ fetch 옵션 ------------- */
  _opts(method, body=null){
    const headers = {"Content-Type":"application/json"};
    if (this.csrfHeader && this.csrfToken) headers[this.csrfHeader] = this.csrfToken;
    return { method, headers, body: body? JSON.stringify(body): undefined };
  }
}
