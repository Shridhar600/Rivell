// Shared behaviour for every page: navigation state and reveal on scroll.
(function () {
  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  window.rivellReduceMotion = reduce;

  // Theme: follows the system until the visitor picks one; the choice is remembered.
  var root = document.documentElement;
  var toggle = document.querySelector('[data-theme-toggle]');
  var darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
  var current = function () { return root.getAttribute('data-theme') || (darkQuery.matches ? 'dark' : 'light'); };
  var label = function () {
    if (toggle) toggle.setAttribute('aria-label', current() === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
  };
  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = current() === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('rivell-theme', next); } catch (e) {}
      label();
    });
    if (darkQuery.addEventListener) darkQuery.addEventListener('change', label);
    label();
  }

  var nav = document.querySelector('[data-nav]');
  if (nav) {
    var onScroll = function () { nav.classList.toggle('is-scrolled', window.scrollY > 8); };
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
  }

  var reveals = document.querySelectorAll('.reveal');
  if (reduce || !('IntersectionObserver' in window)) {
    reveals.forEach(function (el) { el.classList.add('is-in'); });
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in');
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    reveals.forEach(function (el) { io.observe(el); });
  }

})();
