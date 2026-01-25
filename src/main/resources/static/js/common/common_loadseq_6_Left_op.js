/* ===============================================================
   ✅ commonLeft_op.js (v1.2 - 충돌 해결 버전)
   ---------------------------------------------------------------
   - 서브메뉴(has-submenu) 클릭 시 열기/닫기 기능만 유지
   - 외부 클릭 닫기/휠 닫기 로직은 layout에서 통합 관리하므로 제거
================================================================ */

document.addEventListener("DOMContentLoaded", () => {
  // left.html fragment 로드 대기
  setTimeout(() => {
    const sidebar = document.getElementById("left"); // ID #left 사용
    if (!sidebar) return;

    // ✅ 서브메뉴 클릭 시 열기/닫기 (이 기능만 담당)
    sidebar.querySelectorAll(".has-submenu > a").forEach(a => {
      // 기존 이벤트 제거를 위해 복제 후 교체 (확실한 초기화)
      const newA = a.cloneNode(true);
      a.parentNode.replaceChild(newA, a);

      newA.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation(); // ❗ 중요: 클릭 이벤트가 window로 퍼지지 않게 차단

        const li = newA.parentElement;
        const submenu = li.querySelector(".submenu");

        if (submenu) {
          const isOpen = li.classList.contains("open");
          if (isOpen) {
            li.classList.remove("open");
            submenu.style.display = "none";
          } else {
            li.classList.add("open");
            submenu.style.display = "block";
          }
        }
      });
    });

    // 🚨 [삭제됨] 기존의 document click, keydown(ESC), overlay click 닫기 로직은
    // default_layout.html의 통합 로직과 충돌하므로 여기서 모두 제거했습니다.
    
  }, 200);
});