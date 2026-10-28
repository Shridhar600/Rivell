// Technology page: the system diagram and the one-clause walkthrough.
(function () {
  var reduce = window.rivellReduceMotion;

  // System diagram: selecting a part lights what it connects to and explains it.
  var arch = document.querySelector('[data-arch]');
  if (arch) {
    var svg = arch.querySelector('svg');
    var panel = arch.querySelector('[data-arch-panel]');
    var list = document.querySelector('[data-arch-list]');
    var links = {
      'k-in': ['e1', 'k-eng'],
      'k-eng': ['k-in', 'e1', 'e2', 'k-you', 'ak', 'rules', 'jev', 'lm'],
      'k-you': ['e2', 'k-eng', 'e3', 'k-pub'],
      'k-pub': ['e3', 'k-you', 'e4', 'r-eng'],
      'r-in': ['e5', 'r-eng'],
      'r-eng': ['r-in', 'e5', 'e4', 'k-pub', 'e6', 'r-you', 'ar', 'rules', 'jev', 'lm', 'search'],
      'r-you': ['e6', 'r-eng', 'e7', 'r-red'],
      'r-red': ['e7', 'r-you', 'e8', 'r-out', 'ad', 'rules'],
      'r-out': ['e8', 'r-red'],
      'rules': ['ak', 'ar', 'ad', 'k-eng', 'r-eng', 'r-red'],
      'jev': ['ak', 'ar', 'k-eng', 'r-eng'],
      'lm': ['ak', 'ar', 'k-eng', 'r-eng'],
      'search': ['ar', 'r-eng']
    };
    var nodes = svg.querySelectorAll('.node');
    var edges = svg.querySelectorAll('.edge');
    var selected = null;

    var light = function (id) {
      var lit = id ? [id].concat(links[id]) : [];
      arch.classList.toggle('has-focus', !!id);
      nodes.forEach(function (n) { n.classList.toggle('is-lit', lit.indexOf(n.getAttribute('data-node')) > -1); });
      edges.forEach(function (e) { e.classList.toggle('is-lit', lit.indexOf(e.getAttribute('data-edge')) > -1); });
    };
    var explain = function (id) {
      var item = list.querySelector('[data-for="' + id + '"]');
      if (!item) return;
      panel.innerHTML = '<p class="k">' + item.querySelector('.k').textContent + '</p><div>' +
        '<h4>' + item.querySelector('h4').textContent + '</h4><p>' + item.querySelector('h4 + p').textContent + '</p></div>';
    };
    var select = function (id) {
      selected = id;
      light(id);
      explain(id);
      nodes.forEach(function (n) { n.setAttribute('aria-pressed', n.getAttribute('data-node') === id ? 'true' : 'false'); });
    };

    nodes.forEach(function (n) {
      var id = n.getAttribute('data-node');
      n.setAttribute('aria-pressed', 'false');
      n.addEventListener('mouseenter', function () { light(id); });
      n.addEventListener('mouseleave', function () { light(selected); });
      n.addEventListener('click', function () { select(id); });
      n.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(id); }
      });
    });

    if (reduce && svg.pauseAnimations) svg.pauseAnimations();
    if (reduce) svg.querySelector('.packets').style.display = 'none';
  }

  // One clause, step by step.
  var journey = document.querySelector('[data-journey]');
  if (journey) {
    var steps = journey.querySelectorAll('.j-step');
    var panels = journey.querySelectorAll('[data-panel]');
    var autoBtn = journey.querySelector('[data-j="auto"]');
    var caption = journey.querySelector('[data-j-caption]');
    var current = 0;
    var timer = null;

    var bars = function (scope, on) {
      scope.querySelectorAll('.fill').forEach(function (f) { f.style.width = on ? f.getAttribute('data-w') + '%' : '0'; });
    };
    var go = function (i) {
      current = (i + steps.length) % steps.length;
      steps.forEach(function (s, j) {
        s.classList.toggle('is-active', j === current);
        s.querySelector('button').setAttribute('aria-expanded', j === current ? 'true' : 'false');
      });
      panels.forEach(function (p, j) {
        p.classList.toggle('is-active', j === current);
        bars(p, j === current);
      });
      var step = steps[current];
      caption.innerHTML = '<span class="n">' + step.querySelector('.n').textContent + '</span><b>' +
        step.querySelector('.h').textContent + '</b><p>' + step.querySelector('.d p').textContent + '</p>';
    };
    var stopAuto = function () {
      clearInterval(timer);
      timer = null;
      autoBtn.setAttribute('aria-pressed', 'false');
      autoBtn.textContent = 'Play steps';
    };
    var startAuto = function () {
      if (timer) return;
      autoBtn.setAttribute('aria-pressed', 'true');
      autoBtn.textContent = 'Pause';
      timer = setInterval(function () { go(current + 1); }, 4200);
    };

    steps.forEach(function (s, j) {
      s.querySelector('button').addEventListener('click', function () { stopAuto(); go(j); });
    });
    journey.querySelector('[data-j="prev"]').addEventListener('click', function () { stopAuto(); go(current - 1); });
    journey.querySelector('[data-j="next"]').addEventListener('click', function () { stopAuto(); go(current + 1); });
    autoBtn.addEventListener('click', function () { if (timer) stopAuto(); else startAuto(); });
    go(0);

    // Hold the panel area at the height of the tallest panel, so the page does not jump.
    var screen = journey.querySelector('.j-screen');
    var fit = function () {
      var tallest = 0;
      panels.forEach(function (p) { tallest = Math.max(tallest, p.offsetHeight); });
      screen.style.minHeight = tallest + 'px';
    };
    fit();
    window.addEventListener('resize', fit);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(fit);

    if (!reduce && 'IntersectionObserver' in window) {
      var started = false;
      new IntersectionObserver(function (entries) {
        var inView = entries[0].isIntersecting;
        if (inView && !started) { started = true; startAuto(); }
        if (!inView && timer) stopAuto();
      }, { threshold: 0.45 }).observe(journey);
    }
  }

  // Static likelihood bars fill when they come into view.
  document.querySelectorAll('[data-bars-static]').forEach(function (group) {
    if (reduce || !('IntersectionObserver' in window)) { bars2(group); return; }
    var io = new IntersectionObserver(function (entries) {
      if (entries[0].isIntersecting) { bars2(group); io.disconnect(); }
    }, { threshold: 0.4 });
    io.observe(group);
  });
  function bars2(group) {
    group.querySelectorAll('.fill').forEach(function (f) { f.style.width = f.getAttribute('data-w') + '%'; });
  }
})();
