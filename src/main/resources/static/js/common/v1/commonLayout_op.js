/**
 * 🧩 commonLayout_op.js
 * --------------------------------------------------------
 * ✅ 사이드바 / 오버레이 / 메뉴 토글 담당
 * ✅ 중복 로드 방지
 * --------------------------------------------------------
 */
(function () {
  if (window.__COMMON_LAYOUT_LOADED__) return;
  window.__COMMON_LAYOUT_LOADED__ = true;

  document.addEventListener("DOMContentLoaded", () => {
    const sidebar = document.querySelector(".sidebar");
    const overlay = document.getElementById("sidebarOverlay");

    if (!sidebar || !overlay) return;

    window.toggleSidebar = function () {
      const isOpen = sidebar.classList.contains("open");
      sidebar.classList.toggle("open", !isOpen);
      overlay.classList.toggle("active", !isOpen);
      overlay.style.display = !isOpen ? "block" : "none";
    };

    overlay.addEventListener("click", () => {
      sidebar.classList.remove("open");
      overlay.classList.remove("active");
      overlay.style.display = "none";
    });

    overlay.style.display = "none";
  });
})();
