/* ===============================================================
   ✅ commonLeft_op.js (v1.1 - 2025.11 실전 안정판)
   ---------------------------------------------------------------
   - 사이드바 토글 (open/close)
   - 서브메뉴 클릭 열기/닫기 정상화
   - ESC / 외부 클릭 / 오버레이 닫기
================================================================ */

document.addEventListener("DOMContentLoaded", () => {
  // left.html fragment 로드 후 약간 지연 실행
  setTimeout(() => {
    const sidebar = document.querySelector(".sidebar");
    const overlay = document.getElementById("sidebarOverlay");
    if (!sidebar) return;

    // ✅ 서브메뉴 클릭 시 열기/닫기
    sidebar.querySelectorAll(".has-submenu > a").forEach(a => {
      a.addEventListener("click", (e) => {
        e.preventDefault();
        const li = a.parentElement;
        li.classList.toggle("open");
      });
    });

    // ✅ 외부 클릭 시 닫기
    document.addEventListener("click", (e) => {
      if (!sidebar.contains(e.target) && !e.target.closest("#menuToggle")) {
        sidebar.classList.remove("open");
        overlay?.classList.remove("active");
        overlay.style.display = "none";
      }
    });

    // ✅ ESC 키로 닫기
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        sidebar.classList.remove("open");
        overlay?.classList.remove("active");
        overlay.style.display = "none";
      }
    });

    // ✅ 오버레이 클릭 시 닫기
    overlay?.addEventListener("click", () => {
      sidebar.classList.remove("open");
      overlay.classList.remove("active");
      overlay.style.display = "none";
    });

    // ✅ 전역 함수: 상단 토글 버튼 연결
    window.toggleSidebar = () => {
      const isOpen = sidebar.classList.contains("open");
      sidebar.classList.toggle("open", !isOpen);
      overlay.classList.toggle("active", !isOpen);
      overlay.style.display = !isOpen ? "block" : "none";
    };
  }, 200);
});
