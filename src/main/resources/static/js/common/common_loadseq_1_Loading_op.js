/* ===============================================
   common_loadseq_1_Loading_op.js — (v1.0)
   테이블/콘텐츠 자동 감지 로딩 스피너
   =============================================== */

window.CommonLoading = {
  /**
   * 로딩 표시
   * @param {"auto"|"table"|"center"} mode
   */
  show(mode = "auto") {
    let container;

    if (mode === "table") {
      container = document.querySelector(".table-container");
    } else if (mode === "center") {
      container = document.querySelector(".content-container") || document.body;
    } else {
      // auto: 테이블 존재 여부 감지
      container = document.querySelector(".table-container")
        ? document.querySelector(".table-container")
        : document.querySelector(".content-container") || document.body;
    }

    if (!container) return;

    // 중복 방지
    if (container.querySelector(".inline-loader")) return;

    const loader = document.createElement("div");
    loader.className = "inline-loader";
    loader.innerHTML = `<div class="spinner"></div>`;
    container.style.position = "relative";
    container.appendChild(loader);
  },

  /** 로딩 해제 */
  hide() {
    document.querySelectorAll(".inline-loader").forEach(el => el.remove());
  }
};
