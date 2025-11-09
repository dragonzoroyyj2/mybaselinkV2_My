/**
 * ===============================================================
 * ✅ common_loadseq_2_Notify.js (v1.0)
 * ---------------------------------------------------------------
 * - 중복 메시지 자동 방지 (같은 메시지 연속 표시 X)
 * - 여러 알림을 동시에 우측 하단에 표시
 * - commonToast.css 와 완전 연동
 * - 자동 fadeOut 애니메이션 (CSS 기반)
 * ===============================================================
 */

window.Toast = (() => {
  const containerId = "toastContainer";
  let lastMsg = "";

  /** ✅ 토스트 표시 */
  function show(message, type = "info", duration = 1500) { // 토스트 사라지는속도
    if (!message || message === lastMsg) return;
    lastMsg = message;

    // 컨테이너 생성 (없을 경우만)
    let container = document.getElementById(containerId);
    if (!container) {
      container = document.createElement("div");
      container.id = containerId;
      container.className = "toast-container";
      document.body.appendChild(container);
    }

    // 토스트 생성
    const toast = document.createElement("div");
    toast.className = `toast ${type}`;
    toast.innerHTML = `
      <i class="fas ${
        type === "success" ? "fa-check-circle" :
        type === "error"   ? "fa-times-circle" :
        type === "warning" ? "fa-exclamation-triangle" :
                             "fa-info-circle"
      }"></i>
      <span>${message}</span>
      <i class="fas fa-times close-toast" title="닫기"></i>
    `;

    container.appendChild(toast);

    // 닫기 버튼 이벤트
    toast.querySelector(".close-toast").addEventListener("click", () => hideToast(toast));

    // 자동 제거
    setTimeout(() => hideToast(toast), duration);
  }

  /** ✅ 토스트 제거 (fadeOut 애니메이션 적용) */
  function hideToast(toast) {
    toast.classList.add("hide");
    setTimeout(() => toast.remove(), 350);
    lastMsg = "";
  }

  return { show };
})();
