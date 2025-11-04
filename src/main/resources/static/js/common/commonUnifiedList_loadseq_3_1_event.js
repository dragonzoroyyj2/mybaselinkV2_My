/* ===============================================================
   ✅ commonUnifiedList_event.js (v1.9 - 2025.11 완전 분리판)
   ---------------------------------------------------------------
   - UnifiedList의 전역 이벤트 처리 담당
   - 검색, 추가, 수정, 삭제, 엑셀, 상세보기 클릭 이벤트 관리
   - ESC/배경 닫기 및 폼 입력 감지 포함
================================================================ */

/* ----------------------------------------------------------
   🧭 이벤트 전체 바인딩
---------------------------------------------------------- */
UnifiedList.prototype._bindGlobalEvents = function () {
  document.removeEventListener("click", this._clickHandler);
  this._clickHandler = this._onClick.bind(this);
  document.addEventListener("click", this._clickHandler);

  // ✅ 검색 버튼
  if (this.config.searchBtnSelector) {
    const btn = document.querySelector(this.config.searchBtnSelector);
    if (btn) {
      btn.onclick = () => {
        this.loadList(0, "force");
      };
    }
  }

  // ✅ 검색창 엔터키
  if (this.config.searchInputSelector) {
    const input = document.querySelector(this.config.searchInputSelector);
    if (input) {
      input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") this.loadList(0, "force");
      });
    }
  }

  // ✅ 추가 버튼
  if (this.config.addBtnSelector) {
    const btn = document.querySelector(this.config.addBtnSelector);
    if (btn) {
      btn.onclick = () => {
        openModal(this.config.modalId);
        resetModalForm(this.config.modalId);
        this.config.onAddModalOpen?.();
      };
    }
  }

  // ✅ 저장 버튼
  if (this.config.saveBtnSelector) {
    const btn = document.querySelector(this.config.saveBtnSelector);
    if (btn) {
      btn.onclick = async () => {
        await this._handleSave();
      };
    }
  }

  // ✅ 수정 버튼
  if (this.config.updateBtnSelector) {
    const btn = document.querySelector(this.config.updateBtnSelector);
    if (btn) {
      btn.onclick = async () => {
        await this._handleUpdate();
      };
    }
  }

  // ✅ 삭제 버튼
  if (this.config.deleteSelectedBtnSelector) {
    const btn = document.querySelector(this.config.deleteSelectedBtnSelector);
    if (btn) {
      btn.onclick = async () => {
        await this._handleDeleteSelected();
      };
    }
  }

  // ✅ 엑셀 다운로드
  if (this.config.excelBtnSelector) {
    const btn = document.querySelector(this.config.excelBtnSelector);
    if (btn) {
      btn.onclick = async () => {
        await this._handleExcelDownload();
      };
    }
  }
};

/* ----------------------------------------------------------
   🖱️ 공통 클릭 이벤트 위임
   ----------------------------------------------------------
   - 상세보기 링크 클릭
   - 체크박스 전체 선택
---------------------------------------------------------- */
UnifiedList.prototype._onClick = function (e) {
  const target = e.target;

  // ✅ 상세보기 링크 클릭
  if (target.matches(".detail-link")) {
    e.preventDefault();
    const id = target.dataset.id;
    this._openDetailModal(id);
    return;
  }

  // ✅ 전체선택 체크박스
  if (target.matches(this.config.checkAllSelector)) {
    const isChecked = target.checked;
    document
      .querySelectorAll(".row-checkbox")
      .forEach((chk) => (chk.checked = isChecked));
  }
};

/* ----------------------------------------------------------
   💾 저장 로직 (등록)
---------------------------------------------------------- */
UnifiedList.prototype._handleSave = async function () {
  const modal = document.querySelector(this.config.modalId);
  if (!modal) return;

  if (!this._validateRequired(this.config.modalId)) return;

  try {
    this._showModalLoading(modal);
    const body = this._collectFormData(modal);
    const res = await fetch(this.config.apiUrl, this._opts("POST", body));
    if (!res.ok) throw new Error("등록 실패");
    Toast.show("✅ 등록 완료", "success");
    closeModal(this.config.modalId);
    this.loadList(0, "force");
  } catch (err) {
    console.error(err);
    Toast.show("등록 중 오류가 발생했습니다.", "error");
  } finally {
    this._hideModalLoading(modal);
  }
};

/* ----------------------------------------------------------
   📝 수정 로직
---------------------------------------------------------- */
UnifiedList.prototype._handleUpdate = async function () {
  const modal = document.querySelector(this.config.detailModalId);
  if (!modal) return;

  if (!this._validateRequired(this.config.detailModalId)) return;

  try {
    this._showModalLoading(modal);
    const body = this._collectFormData(modal);
    const res = await fetch(this.config.apiUrl, this._opts("PUT", body));
    if (!res.ok) throw new Error("수정 실패");
    Toast.show("✏️ 수정 완료", "success");
    closeModal(this.config.detailModalId);
    this.loadList(this.currentPage, "force");
  } catch (err) {
    console.error(err);
    Toast.show("수정 중 오류가 발생했습니다.", "error");
  } finally {
    this._hideModalLoading(modal);
  }
};

/* ----------------------------------------------------------
   🗑️ 선택 삭제
---------------------------------------------------------- */
UnifiedList.prototype._handleDeleteSelected = async function () {
  const ids = Array.from(
    document.querySelectorAll(".row-checkbox:checked")
  ).map((el) => el.dataset.id);

  if (!ids.length) {
    Toast.show("선택된 항목이 없습니다.", "warning");
    return;
  }

  if (!confirm(`${ids.length}건을 삭제하시겠습니까?`)) return;

  try {
    CommonLoading.show("center");
    const res = await fetch(this.config.apiUrl, this._opts("DELETE", ids));
    if (!res.ok) throw new Error("삭제 실패");
    Toast.show("🗑️ 삭제 완료", "success");
    this.config.onDeleteSuccess?.(ids);
    this.loadList(this.currentPage, "force");
  } catch (err) {
    console.error(err);
    Toast.show("삭제 중 오류가 발생했습니다.", "error");
  } finally {
    CommonLoading.hide();
  }
};

/* ----------------------------------------------------------
   📊 엑셀 다운로드
---------------------------------------------------------- */
UnifiedList.prototype._handleExcelDownload = async function () {
  try {
    CommonLoading.show("center");
    const res = await fetch(
      `${this.config.apiUrl.replace("/list", "/excel")}?mode=${this.config.mode}`,
      this._opts("GET")
    );
    if (!res.ok) throw new Error("엑셀 다운로드 실패");

    const blob = await res.blob();
    const blobUrl = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    const filename = this.config.excelFileName || "data.xlsx";
    a.href = blobUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(blobUrl);
    Toast.show(`${filename} 다운로드 완료`, "success");
  } catch (err) {
    console.error(err);
    Toast.show("엑셀 다운로드 중 오류 발생", "error");
  } finally {
    CommonLoading.hide();
  }
};

/* ----------------------------------------------------------
   🔍 상세보기 모달 열기
---------------------------------------------------------- */
UnifiedList.prototype._openDetailModal = async function (id) {
  if (!id) return;
  try {
    CommonLoading.show("center");
    const res = await fetch(`${this.config.apiUrl}/${id}`, this._opts("GET"));
    if (!res.ok) throw new Error("조회 실패");
    const data = await res.json();

    openModal(this.config.detailModalId);
    this._fillDetailModal(data);
    this.config.onDetailModalOpen?.(id);
  } catch (err) {
    console.error(err);
    Toast.show("상세 조회 중 오류 발생", "error");
  } finally {
    CommonLoading.hide();
  }
};
