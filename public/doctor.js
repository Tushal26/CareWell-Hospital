/* ==========================================================================
   CareWell Hospital — doctor.js
   ========================================================================== */

document.addEventListener('DOMContentLoaded', function () {

  var HEARTBEAT_INTERVAL_MS = 20000; // keep this doctor "online" server-side
  var DASHBOARD_POLL_MS = 5000;      // how often the dashboard refreshes itself

  var SPECIALTY_LABELS = {
    general: 'General Medicine',
    cardiology: 'Cardiology',
    neurology: 'Neurology',
    maternity: 'Maternity & Child Care',
    surgery: 'Surgery',
    diagnostics: 'Pathology & Diagnostics'
  };

  function specialtyLabel(value) {
    return SPECIALTY_LABELS[value] || value;
  }

  function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML;
  }

  function formatDate(isoDate) {
    if (!isoDate) return null;
    var parts = isoDate.split('-');
    var d = new Date(parts[0], parts[1] - 1, parts[2]);
    return d.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' });
  }

  // Small wrapper around fetch: sends/receives JSON, and always resolves
  // with { ok, status, data } instead of throwing on a non-2xx response,
  // since the server's error responses are still valid JSON we want to read.
  function api(path, options) {
    options = options || {};
    var opts = {
      method: options.method || 'GET',
      headers: { 'Content-Type': 'application/json' }
    };
    if (options.body) opts.body = JSON.stringify(options.body);

    return fetch(path, opts)
      .then(function (res) {
        return res.json().then(function (data) {
          return { ok: res.ok, status: res.status, data: data };
        });
      })
      .catch(function () {
        return { ok: false, status: 0, data: { error: 'Could not reach the server. Is it running?' } };
      });
  }


  /* ------------------------------------------------------------------
     MOBILE NAV — hamburger toggle
     (Duplicated in patient.js — pure UI, no data involved.)
  ------------------------------------------------------------------ */
  var navToggle = document.getElementById('navToggle');
  var navPanel = document.getElementById('navPanel');

  if (navToggle && navPanel) {
    var setIcon = function (isOpen) {
      navToggle.innerHTML = isOpen
        ? '<i class="fa-solid fa-xmark"></i>'
        : '<i class="fa-solid fa-bars"></i>';
      navToggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    };

    navToggle.addEventListener('click', function () {
      var isOpen = navPanel.classList.toggle('open');
      setIcon(isOpen);
    });

    navPanel.querySelectorAll('a').forEach(function (link) {
      link.addEventListener('click', function () {
        navPanel.classList.remove('open');
        setIcon(false);
      });
    });
  }


  /* ------------------------------------------------------------------
     DOCTOR SIGN UP  ->  POST /api/doctor/signup
  ------------------------------------------------------------------ */
  var signupForm = document.getElementById('doctorSignupForm');

  if (signupForm) {
    var signupResult = document.getElementById('signupResult');

    signupForm.addEventListener('submit', function (e) {
      e.preventDefault();

      var name = document.getElementById('fullName').value.trim();
      var degree = document.getElementById('degree').value.trim();
      var specialtySelect = document.getElementById('specialty');
      var specialty = specialtySelect.value;
      var username = document.getElementById('signupUsername').value.trim();
      var password = document.getElementById('signupPassword').value;

      api('/api/doctor/signup', {
        method: 'POST',
        body: { name: name, degree: degree, specialty: specialty, username: username, password: password }
      }).then(function (res) {
        if (!res.ok) {
          signupResult.classList.add('error');
          signupResult.innerHTML =
            '<i class="fa-solid fa-circle-exclamation"></i>' +
            '<div><strong>Could not create account</strong><p>' + escapeHtml(res.data.error || 'Please try again.') + '</p></div>';
          signupResult.hidden = false;
          return;
        }

        signupResult.classList.remove('error');
        signupResult.innerHTML =
          '<i class="fa-solid fa-circle-check"></i>' +
          '<div><strong>Account created!</strong><p>Taking you to the login page\u2026</p></div>';
        signupResult.hidden = false;

        setTimeout(function () { window.location.href = 'doctor-login.html'; }, 1200);
      });
    });
  }


  /* ------------------------------------------------------------------
     DOCTOR LOGIN  ->  POST /api/doctor/login
     The server marks this doctor "online" the moment login succeeds.
  ------------------------------------------------------------------ */
  var loginForm = document.getElementById('doctorLoginForm');

  if (loginForm) {
    var loginResult = document.getElementById('loginResult');

    loginForm.addEventListener('submit', function (e) {
      e.preventDefault();

      var username = document.getElementById('username').value.trim();
      var password = document.getElementById('password').value;

      api('/api/doctor/login', { method: 'POST', body: { username: username, password: password } })
        .then(function (res) {
          if (!res.ok) {
            loginResult.classList.add('error');
            loginResult.innerHTML =
              '<i class="fa-solid fa-circle-exclamation"></i>' +
              '<div><strong>Invalid username or password</strong><p>Double-check your details, or create an account if you\u2019re new here.</p></div>';
            loginResult.hidden = false;
            return;
          }

          sessionStorage.setItem('cw_current_doctor', JSON.stringify(res.data.doctor));
          window.location.href = 'doctor-dashboard.html';
        });
    });
  }


  /* ------------------------------------------------------------------
     DOCTOR DASHBOARD
  ------------------------------------------------------------------ */
  var dashboardRoot = document.getElementById('dashboardRoot');

  if (dashboardRoot) {
    var raw = sessionStorage.getItem('cw_current_doctor');

    if (!raw) {
      window.location.href = 'doctor-login.html';
    } else {
      var doctor = JSON.parse(raw);

      document.getElementById('doctorNameDisplay').textContent = doctor.name || 'Doctor';
      document.getElementById('doctorMetaDisplay').textContent =
        (doctor.degree ? doctor.degree + ' \u2022 ' : '') + specialtyLabel(doctor.specialty);

      var heartbeatTimer = setInterval(function () {
        api('/api/doctor/heartbeat', { method: 'POST', body: { doctorId: doctor.id } });
      }, HEARTBEAT_INTERVAL_MS);

      window.addEventListener('beforeunload', function () {
        api('/api/doctor/logout', { method: 'POST', body: { doctorId: doctor.id } });
      });

      var logoutBtn = document.getElementById('logoutBtn');
      if (logoutBtn) {
        logoutBtn.addEventListener('click', function () {
          clearInterval(heartbeatTimer);
          clearInterval(pollTimer);
          api('/api/doctor/logout', { method: 'POST', body: { doctorId: doctor.id } }).then(function () {
            sessionStorage.removeItem('cw_current_doctor');
            window.location.href = 'doctor-login.html';
          });
        });
      }

      function callNext() {
        api('/api/queue/next', { method: 'POST', body: { doctorId: doctor.id } }).then(function () { render(); });
      }

      function callSpecific(queueId) {
        api('/api/queue/call', { method: 'POST', body: { doctorId: doctor.id, queueId: Number(queueId) } })
          .then(function () { render(); });
      }

      function markDone(queueId) {
        api('/api/queue/done', { method: 'POST', body: { queueId: Number(queueId) } }).then(function () { render(); });
      }

      function markSkipped(queueId) {
        api('/api/queue/skip', { method: 'POST', body: { queueId: Number(queueId) } }).then(function () { render(); });
      }

      function patientMetaLine(p) {
        var bits = [(p.patientAge || '\u2014') + ' yrs', p.patientPhone];
        if (p.preferredDate) bits.push(formatDate(p.preferredDate) || p.preferredDate);
        if (p.preferredTime) bits.push(p.preferredTime);
        return bits.map(escapeHtml).join(' &nbsp;\u2022&nbsp; ');
      }

      function render() {
        api('/api/queue/doctor?doctorId=' + doctor.id).then(function (res) {
          if (!res.ok) return;

          var waitingCount = res.data.waitingCount;
          var seenSoFar = res.data.seenSoFar;
          var totalCount = res.data.totalCount;
          var current = res.data.current;
          var dueWaiting = res.data.queue;

          document.getElementById('statWaiting').textContent = waitingCount;
          document.getElementById('statSeen').textContent = seenSoFar;
          document.getElementById('statTotal').textContent = totalCount;
          document.getElementById('queueCountBadge').textContent = waitingCount + ' waiting';

          var currentPanel = document.getElementById('currentPatientPanel');

          if (current) {
            currentPanel.innerHTML =
              '<div class="current-patient">' +
                '<div class="current-ticket">#' + current.queueNumber + '</div>' +
                '<div class="current-details">' +
                  '<h3>' + escapeHtml(current.patientName) + '</h3>' +
                  '<div class="current-meta">' +
                    '<span><i class="fa-solid fa-cake-candles"></i> ' + escapeHtml(current.patientAge || '\u2014') + ' yrs</span>' +
                    '<span><i class="fa-solid fa-phone"></i> ' + escapeHtml(current.patientPhone) + '</span>' +
                    (current.preferredDate ? '<span><i class="fa-solid fa-calendar-days"></i> ' + escapeHtml(formatDate(current.preferredDate) || current.preferredDate) + '</span>' : '') +
                    (current.preferredTime ? '<span><i class="fa-solid fa-clock"></i> ' + escapeHtml(current.preferredTime) + '</span>' : '') +
                  '</div>' +
                  '<div class="current-actions">' +
                    '<button class="btn btn-primary" id="markDoneBtn"><i class="fa-solid fa-check"></i> Mark as Done</button>' +
                    '<button class="btn btn-ghost" id="skipBtn"><i class="fa-solid fa-forward"></i> Skip</button>' +
                  '</div>' +
                '</div>' +
              '</div>';

            document.getElementById('markDoneBtn').addEventListener('click', function () { markDone(current.id); });
            document.getElementById('skipBtn').addEventListener('click', function () { markSkipped(current.id); });

          } else if (waitingCount > 0) {
            currentPanel.innerHTML =
              '<div class="current-empty">' +
                '<i class="fa-solid fa-clipboard-check"></i>' +
                '<p>No patient currently in session.</p>' +
                '<button class="btn btn-primary" id="callNextBtn"><i class="fa-solid fa-bell"></i> Call Next Patient</button>' +
              '</div>';
            document.getElementById('callNextBtn').addEventListener('click', callNext);

          } else {
            currentPanel.innerHTML =
              '<div class="current-empty">' +
                '<i class="fa-solid fa-mug-hot"></i>' +
                '<p>No patients waiting right now.</p>' +
              '</div>';
          }

          var listEl = document.getElementById('waitingList');

          if (!dueWaiting.length) {
            listEl.innerHTML =
              '<div class="queue-empty">' +
                '<i class="fa-solid fa-clipboard-list"></i>' +
                '<p>No one else is waiting right now.</p>' +
              '</div>';
          } else {
            listEl.innerHTML = dueWaiting.map(function (p) {
              return (
                '<div class="queue-row">' +
                  '<div class="queue-ticket">#' + p.queueNumber + '</div>' +
                  '<div class="queue-info">' +
                    '<strong>' + escapeHtml(p.patientName) + '</strong>' +
                    '<span>' + patientMetaLine(p) + '</span>' +
                  '</div>' +
                  (current ? '' : '<button class="btn btn-ghost btn-sm queue-call-btn" data-id="' + p.id + '"><i class="fa-solid fa-phone-volume"></i> Call</button>') +
                '</div>'
              );
            }).join('');

            listEl.querySelectorAll('.queue-call-btn').forEach(function (btn) {
              btn.addEventListener('click', function () { callSpecific(btn.getAttribute('data-id')); });
            });
          }
        });
      }

      render();
      var pollTimer = setInterval(render, DASHBOARD_POLL_MS);
    }
  }

});