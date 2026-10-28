// Home page: the headline redline, the hero clause loop, the how-it-works stage, the standard sliders.
(function () {
  var reduce = window.rivellReduceMotion;

  // Headline: "their" is struck and "your" is inserted.
  var headline = document.querySelector('[data-headline]');
  if (headline) {
    if (reduce) headline.classList.add('is-played');
    else setTimeout(function () { headline.classList.add('is-played'); }, 650);
  }

  // Hero stage: three clauses, each read, flagged, redlined, accepted and verified.
  var scenes = [
    {
      file: 'Kestrel Assurance - Master Services Agreement.docx',
      title: 'MASTER SERVICES AGREEMENT',
      parties: 'Bramber Software Limited and Kestrel Assurance Group plc',
      before: '12.1 Neither party limits its liability for death or personal injury caused by negligence, or for fraud.',
      heading: '12. Limitation of liability',
      pre: "12.2 Each party's aggregate liability under this Agreement shall not exceed ",
      del: 'three times the annual Fees',
      ins: ' the Fees paid or payable in the 12 months before the claim',
      post: '.',
      after: '12.3 The limit in clause 12.2 applies to each party, and not only to the Supplier.',
      nextHeading: '13. Term and renewal',
      next: '13.1 This Agreement starts on the Effective Date and continues for the Initial Term in the Order Form.',
      noteKey: 'Your playbook · Liability cap',
      notePos: 'Cap at the fees for 12 months. Walk away above two times the annual fees.',
      noteSrc: 'Commercial Contracts Playbook v4.2',
      finding: 'Liability cap is above your limit',
      red: true
    },
    {
      file: 'Kestrel Assurance - Order Form KAG-2026-014.docx',
      title: 'ORDER FORM',
      parties: 'Bramber Software Limited and Kestrel Assurance Group plc',
      before: '4.2 The Subscription Term is thirty-six (36) months from the Start Date.',
      heading: '4. Term and termination',
      pre: '4.3 The Customer may terminate this Order Form for convenience ',
      del: "at any time on thirty (30) days' notice",
      ins: " after the first 12 months, on ninety (90) days' notice",
      post: '.',
      after: '4.4 Fees for the notice period remain payable.',
      nextHeading: '5. Fees and payment',
      next: '5.1 The Customer will pay the Fees annually in advance, within thirty (30) days of the invoice date.',
      noteKey: 'Your playbook · Termination for convenience',
      notePos: "Only after the first 12 months, on 90 days' notice, with fees paid for the notice period.",
      noteSrc: 'Commercial Contracts Playbook v4.2',
      finding: 'Exit right is wider than your position',
      red: true
    },
    {
      file: 'Sablepoint Analytics - Mutual NDA.docx',
      title: 'MUTUAL NON-DISCLOSURE AGREEMENT',
      parties: 'Bramber Software Limited and Sablepoint Analytics Inc.',
      before: '6.1 Each party may use Confidential Information only to evaluate the proposed pilot.',
      heading: '7. Duration',
      pre: '7.1 These obligations continue for ',
      del: 'five (5) years, and for a further three (3) years',
      ins: ' three (3) years',
      post: ' after this Agreement ends.',
      after: '7.2 Each party will return or destroy Confidential Information on request.',
      nextHeading: '8. Governing law',
      next: '8.1 This Agreement is governed by the laws of England and Wales.',
      noteKey: 'Your playbook · Confidentiality',
      notePos: 'Mutual, for the term and three years after it ends. Five years at most.',
      noteSrc: 'NDA Playbook.xlsx',
      finding: 'Confidentiality runs longer than your standard',
      red: false
    }
  ];

  // The stage pins while the visitor scrolls. Scroll moves it through the three clauses,
  // then releases the page. Where it cannot fit the screen, it shows one finished clause.
  var stage = document.querySelector('[data-stage]');
  var stageTrack = document.querySelector('[data-track]');
  var pin = document.querySelector('[data-pin]');
  var nav = document.querySelector('[data-nav]');
  if (stage && stageTrack && pin) {
    var clause = stage.querySelector('[data-s-clause]');
    var accept = stage.querySelector('[data-s-accept]');
    var dot = stage.querySelector('[data-s-dot]');
    var status = stage.querySelector('[data-s="status"]');
    var bars = stage.querySelectorAll('.stage-dots i');
    // Where each step starts within one clause's share of the scroll (0 to 1).
    var marks = [0.06, 0.2, 0.34, 0.44, 0.58, 0.7];
    var current = 0;
    var step = -1;
    var out = false;
    var swap = null;
    var enabled = false;
    var pinTop = 0;
    var ticking = false;

    var fill = function (scene) {
      Object.keys(scene).forEach(function (key) {
        var el = stage.querySelector('[data-s="' + key + '"]');
        if (el) el.textContent = scene[key];
      });
      dot.classList.toggle('is-red', scene.red);
    };
    var show = function (n) {
      step = n;
      var cls = 'stage';
      if (n >= 2) cls += ' is-note';
      if (n >= 3) cls += ' is-strike';
      if (n >= 4) cls += ' is-insert';
      if (n >= 6) cls += ' is-verified';
      if (out) cls += ' is-out';
      stage.className = cls;
      clause.className = 'stage-clause' + (n === 1 ? ' is-reading' : n >= 5 ? ' is-resolved' : n >= 2 ? ' is-flagged' : '');
      accept.className = n >= 5 ? 'ui-btn is-done' : 'ui-btn is-primary';
      accept.textContent = n >= 5 ? 'Accepted' : 'Accept';
      status.textContent = n >= 6 ? 'Redline ready' : 'Reviewing';
    };
    var goScene = function (i) {
      if (i === current) return;
      current = i;
      out = true;
      show(step);
      clearTimeout(swap);
      swap = setTimeout(function () {
        fill(scenes[current]);
        out = false;
        show(step);
      }, 220);
    };

    var update = function () {
      ticking = false;
      if (!enabled) return;
      var rect = stageTrack.getBoundingClientRect();
      var distance = rect.height - pin.offsetHeight;
      var p = Math.min(Math.max((pinTop - rect.top) / distance, 0), 0.9999);
      var i = Math.floor(p * scenes.length);
      var local = p * scenes.length - i;
      var n = 0;
      marks.forEach(function (m) { if (local >= m) n += 1; });
      goScene(i);
      if (n !== step) show(n);
      bars.forEach(function (bar, j) {
        bar.style.transform = 'scaleX(' + (j < i ? 1 : j > i ? 0 : local) + ')';
      });
    };
    var measure = function () {
      var navH = nav ? nav.offsetHeight : 0;
      var room = window.innerHeight - navH;
      var height = pin.offsetHeight;
      enabled = !reduce && height <= room - 16;
      stageTrack.classList.toggle('is-scroll', enabled);
      if (enabled) {
        pinTop = navH + Math.max(8, (room - height) / 2);
        pin.style.top = pinTop + 'px';
        stageTrack.style.setProperty('--stageTrack', Math.round(window.innerHeight * 0.7 * scenes.length) + 'px');
        update();
      } else {
        pin.style.top = '';
        if (current !== 0) { current = 0; fill(scenes[0]); }
        show(6);
      }
    };

    window.addEventListener('scroll', function () {
      if (!ticking) { ticking = true; window.requestAnimationFrame(update); }
    }, { passive: true });
    window.addEventListener('resize', measure);
    measure();
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(measure);
  }

  // How it works: one stage on wide screens, figures inline on narrow ones.
  var how = document.querySelector('[data-how]');
  var howStage = document.querySelector('[data-how-stage]');
  if (how && howStage) {
    var steps2 = Array.prototype.slice.call(how.querySelectorAll('.how-step'));
    var figs = steps2.map(function (s) { return s.querySelector('.fig'); });
    var wide = window.matchMedia('(min-width: 961px)');
    var active = 0;

    var setActive = function (i) {
      active = i;
      steps2.forEach(function (s, j) { s.classList.toggle('is-active', j === i); });
      figs.forEach(function (f, j) { f.classList.toggle('is-active', j === i); });
    };
    var place = function () {
      figs.forEach(function (f, j) {
        if (wide.matches) howStage.appendChild(f);
        else steps2[j].appendChild(f);
      });
      setActive(active);
    };
    place();
    if (wide.addEventListener) wide.addEventListener('change', place);

    if ('IntersectionObserver' in window) {
      var stepIo = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) setActive(steps2.indexOf(entry.target));
        });
      }, { rootMargin: '-45% 0px -45% 0px' });
      steps2.forEach(function (s) { stepIo.observe(s); });

      var figIo = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) entry.target.classList.add('is-shown');
        });
      }, { threshold: 0.25 });
      figs.forEach(function (f) { figIo.observe(f); });
    } else {
      figs.forEach(function (f) { f.classList.add('is-shown'); });
    }
  }

  // Your standard: the same three payment clauses, judged against the visitor's position.
  var std = document.querySelector('[data-standard]');
  if (std) {
    var words = {
      15: 'fifteen (15)', 30: 'thirty (30)', 45: 'forty-five (45)', 60: 'sixty (60)',
      75: 'seventy-five (75)', 90: 'ninety (90)', 105: 'one hundred and five (105)', 120: 'one hundred and twenty (120)'
    };
    var pref = std.querySelector('[data-in="pref"]');
    var fall = std.querySelector('[data-in="fall"]');
    var outPref = std.querySelector('[data-out="pref"]');
    var outFall = std.querySelector('[data-out="fall"]');
    var summary = std.querySelector('[data-summary]');
    var cards = std.querySelectorAll('[data-card]');

    var track = function (input) {
      var pct = (input.value - input.min) / (input.max - input.min) * 100;
      input.style.setProperty('--fill', pct + '%');
    };
    var render = function (changed) {
      var p = +pref.value;
      var f = +fall.value;
      if (f < p) {
        if (changed === pref) { fall.value = p; f = p; } else { pref.value = Math.min(f, +pref.max); p = +pref.value; }
      }
      track(pref);
      track(fall);
      outPref.textContent = p + ' days';
      outFall.textContent = f + ' days';
      summary.textContent = f === p
        ? 'Invoices are payable within ' + p + ' days. There is no fallback.'
        : 'Invoices are payable within ' + p + ' days. A term of up to ' + f + ' days is acceptable as a fallback.';

      cards.forEach(function (card) {
        var d = +card.getAttribute('data-card');
        var cl = card.querySelector('[data-cl]');
        var verdict = card.querySelector('[data-verdict]');
        var kind = d <= p ? 'meets' : d <= f ? 'fallback' : 'beyond';
        if (kind === 'beyond') {
          cl.innerHTML = '<del class="tc">' + words[d] + '</del> <ins class="tc">' + words[p] + '</ins>';
        } else {
          cl.textContent = words[d];
        }
        var text = {
          meets: ['Meets your preferred position.', 'No change.'],
          fallback: ['Within your fallback.', 'Acceptable as drafted.'],
          beyond: [f === p ? 'Outside your position.' : 'Beyond your fallback.', 'Redline proposed.']
        }[kind];
        verdict.className = 'verdict is-' + kind;
        verdict.innerHTML = '<span class="ui-dot"></span>' + text[0] + ' <span class="why">' + text[1] + '</span>';
        if (changed && card.getAttribute('data-kind') !== kind) {
          card.classList.remove('flash');
          void card.offsetWidth;
          card.classList.add('flash');
        }
        card.setAttribute('data-kind', kind);
      });
    };
    pref.addEventListener('input', function () { render(pref); });
    fall.addEventListener('input', function () { render(fall); });
    render(null);
  }
})();
