/**
 * 🧩 commonModal_op.js (v1.0)
 * --------------------------------------------------------
 * ✅ 공통 모달 관리 (열기 / 닫기 / 초기화)
 * ✅ ESC / 배경 클릭 / data-close 버튼 자동 처리
 * --------------------------------------------------------
 */

/**
 * 모달 열기
 * @param {string} modalId - "#addModal" 형태
 * @param {function} [callback] - 모달 열릴 때 실행할 콜백
 */
function openModal(modalId, callback) {
  const modal = document.querySelector(modalId);
  if (!modal) {
    console.error(`모달을 찾을 수 없습니다: ${modalId}`);
    return;
  }
  modal.style.display = "block";
  if (callback) callback();
}

/**
 * 모달 닫기
 * @param {string} modalId - "#addModal" 형태
 */
function closeModal(modalId) {
  const modal = document.querySelector(modalId);
  if (!modal) return;
  modal.style.display = "none";
}

/**
 * 모달 내의 입력폼 초기화
 * @param {string} modalId - "#addModal" 형태
 */
function resetModalForm(modalId) {
  const modal = document.querySelector(modalId);
  if (!modal) return;
  const inputs = modal.querySelectorAll("input, textarea, select");
  inputs.forEach(el => {
    if (el.type === "checkbox" || el.type === "radio") el.checked = false;
    else el.value = "";
  });
}

/**
 * 모든 모달에 대한 전역 이벤트 등록 (1회)
 */
function initGlobalModalEvents() {
  document.addEventListener("click", (e) => {
    const target = e.target;

    // 닫기 버튼
    if (target.matches("[data-close]")) {
      const modalId = "#" + target.dataset.close;
      closeModal(modalId);
    }

    // 배경 클릭 시 닫기
    const modal = target.closest(".modal");
    if (modal && target === modal) {
      modal.style.display = "none";
    }
  });

  // ESC 키로 닫기
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      document.querySelectorAll(".modal").forEach(m => (m.style.display = "none"));
    }
  });
}

// 중복 방지
if (!window._modalEventBound) {
  initGlobalModalEvents();
  window._modalEventBound = true;
}
