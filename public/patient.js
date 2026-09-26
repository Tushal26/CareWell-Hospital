/* ==========================================================================
   CareWell Hospital — patient.js
   ========================================================================== */

document.addEventListener('DOMContentLoaded', function () {

  var STATUS_POLL_MS = 5000; // how often Track Appointment refreshes itself

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
     (Duplicated in doctor.js — pure UI, no data involved.)
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
     BOOK APPOINTMENT FORM
  ------------------------------------------------------------------ */
  var bookingForm = document.getElementById('bookingForm');

  if (bookingForm) {
    var resultBox = document.getElementById('bookingResult');
    var pickerBox = document.getElementById('doctorPicker');
    var pendingBooking = null;

    function doctorPickCardHtml(d, badgeHtml, btnLabel) {
      return (
        '<div class="doctor-pick-card">' +
          '<div class="doctor-pick-avatar"><i class="fa-solid fa-user-doctor"></i></div>' +
          '<div class="doctor-pick-info">' +
            '<strong>Dr. ' + escapeHtml(d.name) + '</strong>' +
            '<span>' + escapeHtml(pendingBooking.specialtyLabel) + (d.degree ? ' &nbsp;\u2022&nbsp; ' + escapeHtml(d.degree) : '') + '</span>' +
            (badgeHtml || '') +
          '</div>' +
          '<button type="button" class="btn btn-primary btn-sm doctor-pick-btn" data-id="' + d.id + '">' + btnLabel + '</button>' +
        '</div>'
      );
    }

    function finalizeBooking(doctorId) {
      var formData = pendingBooking;
      if (!formData) return;

      api('/api/queue/join', {
        method: 'POST',
        body: {
          patientName: formData.name,
          patientPhone: formData.phone,
          patientAge: formData.age,
          doctorId: doctorId,
          preferredDate: formData.date,
          preferredTime: formData.time
        }
      }).then(function (res) {
        if (!res.ok) {
          pickerBox.innerHTML =
            '<div class="booking-result error"><i class="fa-solid fa-circle-exclamation"></i>' +
            '<div><strong>Could not book that appointment</strong><p>' + escapeHtml(res.data.error || 'Please try again.') + '</p></div></div>';
          return;
        }

        var d = res.data;
        var hasSchedule = formData.date !== '' || formData.time !== '';

        if (hasSchedule) {
          var dateText = formatDate(formData.date) || "the date you chose";
          var timeText = formData.time || "the time you chose";

          resultBox.innerHTML =
            '<i class="fa-solid fa-circle-check"></i>' +
            '<div>' +
              '<strong>Thanks, ' + escapeHtml(formData.name || 'there') + '!</strong>' +
              '<p>Your appointment with <strong>Dr. ' + escapeHtml(d.doctorName) + '</strong> (' + escapeHtml(formData.specialtyLabel) + ') is scheduled for <strong>' + escapeHtml(dateText) + '</strong> at <strong>' + escapeHtml(timeText) + '</strong>.</p>' +
              '<p class="queue-number">Your ticket number is <span>#' + d.queueNumber + '</span></p>' +
              '<p><a class="result-link" href="check-status.html">Check availability closer to your appointment time <i class="fa-solid fa-arrow-right"></i></a></p>' +
            '</div>';

        } else {
          var aheadText = d.peopleAhead === 0
            ? 'You\u2019re the first one in line.'
            : (d.peopleAhead === 1 ? 'There is 1 person ahead of you.' : 'There are ' + d.peopleAhead + ' people ahead of you.');

          resultBox.innerHTML =
            '<i class="fa-solid fa-circle-check"></i>' +
            '<div>' +
              '<strong>Thanks, ' + escapeHtml(formData.name || 'there') + '!</strong>' +
              '<p>You\u2019ve been added to <strong>Dr. ' + escapeHtml(d.doctorName) + '</strong>\u2019s queue. ' + aheadText + '</p>' +
              '<p class="queue-number">Your queue number is <span>#' + d.queueNumber + '</span></p>' +
              '<p>Our team will call <strong>' + escapeHtml(formData.phone) + '</strong> when it\u2019s your turn.</p>' +
              '<p><a class="result-link" href="check-status.html">Check your status anytime <i class="fa-solid fa-arrow-right"></i></a></p>' +
            '</div>';
        }

        pickerBox.hidden = true;
        pickerBox.innerHTML = '';
        resultBox.hidden = false;
        resultBox.classList.remove('error');
        resultBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
        bookingForm.reset();
        pendingBooking = null;
      });
    }

    function renderPicker(doctorsList, hasSchedule) {
      var formData = pendingBooking;

      if (!doctorsList.length) {
        pickerBox.innerHTML =
          '<div class="booking-result error"><i class="fa-solid fa-circle-exclamation"></i>' +
          '<div><strong>' + (hasSchedule ? 'No doctors registered yet' : 'No ' + escapeHtml(formData.specialtyLabel) + ' doctors are available right now') + '</strong>' +
          '<p>' + (hasSchedule
              ? 'There are currently no doctors registered under ' + escapeHtml(formData.specialtyLabel) + '. Please try another specialty or check back later.'
              : 'None of our ' + escapeHtml(formData.specialtyLabel) + ' doctors are currently online. Try picking a preferred date and time instead, or check back shortly.'
            ) + '</p></div></div>';
        pickerBox.hidden = false;
        pickerBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
        return;
      }

      if (!hasSchedule) {
        pickerBox.innerHTML =
          '<div class="doctor-picker-head"><i class="fa-solid fa-circle-check"></i> ' +
            doctorsList.length + (doctorsList.length === 1 ? ' doctor is' : ' doctors are') + ' currently available for ' + escapeHtml(formData.specialtyLabel) +
          '</div>' +
          '<div class="doctor-pick-list">' +
            doctorsList.map(function (d) { return doctorPickCardHtml(d, '<span class="status-pill waiting">Online now</span>', 'Book Appointment'); }).join('') +
          '</div>';
      } else {
        pickerBox.innerHTML =
          '<div class="doctor-picker-head"><i class="fa-solid fa-calendar-check"></i> Choose your ' + escapeHtml(formData.specialtyLabel) + ' doctor</div>' +
          '<div class="doctor-pick-list">' +
            doctorsList.map(function (d) { return doctorPickCardHtml(d, '', 'Select'); }).join('') +
          '</div>';
      }

      pickerBox.hidden = false;

      pickerBox.querySelectorAll('.doctor-pick-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
          finalizeBooking(Number(btn.getAttribute('data-id')));
        });
      });

      pickerBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    function showDoctorPicker() {
      var formData = pendingBooking;
      var hasSchedule = formData.date !== '' || formData.time !== '';

      if (!hasSchedule) {
        api('/api/doctors/available?specialty=' + encodeURIComponent(formData.specialty))
          .then(function (res) {
            renderPicker(res.ok ? res.data : [], false);
          });
      } else {
        api('/api/doctors').then(function (res) {
          var all = res.ok ? res.data : [];
          var matching = all.filter(function (d) { return d.specialty === formData.specialty; });
          renderPicker(matching, true);
        });
      }
    }

    bookingForm.addEventListener('submit', function (e) {
      e.preventDefault();

      var specialtySelect = document.getElementById('doctor'); // this select is the SPECIALTY picker
      var specialty = specialtySelect.value;
      var specialtyLabel = specialtySelect.options[specialtySelect.selectedIndex].text;

      pendingBooking = {
        name: document.getElementById('fullName').value.trim(),
        phone: document.getElementById('phone').value.trim(),
        age: document.getElementById('age').value.trim(),
        specialty: specialty,
        specialtyLabel: specialtyLabel,
        date: document.getElementById('date').value.trim(),
        time: document.getElementById('time').value.trim()
      };

      resultBox.hidden = true;
      showDoctorPicker();
    });
  }


  /* ------------------------------------------------------------------
     TRACK APPOINTMENT (patient-facing status check)
  ------------------------------------------------------------------ */
  var statusForm = document.getElementById('statusForm');

  if (statusForm) {
    var statusResults = document.getElementById('statusResults');
    var lastSearch = null;
    var pollTimer = null;

    function statusPill(status) {
      var map = {
        'waiting': ['Waiting', 'waiting'],
        'in-consultation': ['Being Seen', 'in-progress'],
        'done': ['Completed', 'done'],
        'skipped': ['Missed', 'skipped'],
        'cancelled': ['Cancelled', 'skipped']
      };
      var entry = map[status] || ['Unknown', ''];
      return '<span class="status-pill ' + entry[1] + '">' + entry[0] + '</span>';
    }

    function reassign(queueId, newDoctorId) {
      api('/api/queue/reassign', { method: 'POST', body: { queueId: Number(queueId), newDoctorId: Number(newDoctorId) } })
        .then(function () { runSearch(); });
    }

    function cancel(queueId) {
      api('/api/queue/cancel', { method: 'POST', body: { queueId: Number(queueId) } })
        .then(function () { runSearch(); });
    }

    function renderResults(matches) {
      if (!matches.length) {
        statusResults.innerHTML =
          '<div class="booking-result error">' +
            '<i class="fa-solid fa-circle-exclamation"></i>' +
            '<div><strong>No appointment found</strong>' +
            '<p>Double-check the phone number' + (lastSearch.name ? ' and name' : '') + ', or contact the front desk for help.</p></div>' +
          '</div>';
        statusResults.hidden = false;
        return Promise.resolve();
      }

      // For any "waiting but doctor offline" entries we also need the list
      // of other online doctors of that specialty, to offer as a switch.
      var needsAlternatives = matches.filter(function (p) {
        return p.status === 'waiting' && p.isDue && !p.doctorOnline;
      });

      var alternativesLookups = needsAlternatives.map(function (p) {
        return api('/api/doctors/available?specialty=' + encodeURIComponent(p.specialty))
          .then(function (res) { return { specialty: p.specialty, doctorId: p.doctorId, list: res.ok ? res.data : [] }; });
      });

      return Promise.all(alternativesLookups).then(function (altResults) {
        function altsFor(p) {
          var found = altResults.find(function (a) { return a.specialty === p.specialty && a.doctorId === p.doctorId; });
          return found ? found.list.filter(function (d) { return d.id !== p.doctorId; }) : [];
        }

        statusResults.innerHTML = matches.map(function (p) {
          var messageHtml = '';
          var actionsHtml = '';

          if (p.status === 'waiting') {

            if (!p.isDue) {
              messageHtml =
                '<p>Scheduled for <strong>' + escapeHtml(formatDate(p.preferredDate) || p.preferredDate) + '</strong> at <strong>' + escapeHtml(p.preferredTime || 'a time you chose') + '</strong> with <strong>Dr. ' + escapeHtml(p.doctorName) + '</strong>.</p>' +
                '<p class="muted-note">We\u2019ll check Dr. ' + escapeHtml(p.doctorName) + '\u2019s availability once your appointment time arrives.</p>';

            } else if (p.doctorOnline) {
              messageHtml = '<p>Dr. ' + escapeHtml(p.doctorName) + ' is available. ' +
                (p.peopleAhead === 0 ? 'You\u2019re next!' : (p.peopleAhead === 1 ? 'There is 1 person ahead of you.' : 'There are ' + p.peopleAhead + ' people ahead of you.')) +
                '</p>';

            } else {
              var others = altsFor(p);
              messageHtml = '<p class="unavailable-msg">Dr. ' + escapeHtml(p.doctorName) + ' is not currently available.</p>';

              if (others.length) {
                actionsHtml =
                  '<p class="reassign-label">Other available doctors:</p>' +
                  '<div class="doctor-pick-list">' +
                    others.map(function (d) {
                      return (
                        '<div class="doctor-pick-card">' +
                          '<div class="doctor-pick-avatar"><i class="fa-solid fa-user-doctor"></i></div>' +
                          '<div class="doctor-pick-info"><strong>Dr. ' + escapeHtml(d.name) + '</strong><span>' + escapeHtml(p.specialty) + '</span></div>' +
                          '<button type="button" class="btn btn-primary btn-sm reassign-btn" data-id="' + p.queueId + '" data-newid="' + d.id + '">Select</button>' +
                        '</div>'
                      );
                    }).join('') +
                  '</div>' +
                  '<button type="button" class="btn btn-ghost btn-sm cancel-btn" data-id="' + p.queueId + '"><i class="fa-solid fa-xmark"></i> Cancel Appointment</button>';
              } else {
                actionsHtml =
                  '<p class="muted-note">No other doctors of that specialty are online right now.</p>' +
                  '<button type="button" class="btn btn-ghost btn-sm cancel-btn" data-id="' + p.queueId + '"><i class="fa-solid fa-xmark"></i> Cancel Appointment</button>';
              }
            }

          } else if (p.status === 'in-consultation') {
            messageHtml = '<p>You\u2019re being called now by Dr. ' + escapeHtml(p.doctorName) + ' \u2014 please head to the room.</p>';
          } else if (p.status === 'done') {
            messageHtml = '<p>This appointment has been completed.</p>';
          } else if (p.status === 'skipped') {
            messageHtml = '<p>You were marked as a no-show for this appointment. Please contact the front desk to rebook.</p>';
          } else if (p.status === 'cancelled') {
            messageHtml = '<p>This appointment was cancelled.</p>';
          }

          return (
            '<div class="status-card">' +
              '<div class="status-card-head">' +
                '<div class="status-ticket">#' + p.queueNumber + '</div>' +
                '<div class="status-card-title">' +
                  '<strong>' + escapeHtml(p.specialty) + (p.doctorName ? ' \u2014 Dr. ' + escapeHtml(p.doctorName) : '') + '</strong>' +
                '</div>' +
                statusPill(p.status) +
              '</div>' +
              '<div class="status-card-body">' + messageHtml + actionsHtml + '</div>' +
            '</div>'
          );
        }).join('');

        statusResults.querySelectorAll('.reassign-btn').forEach(function (btn) {
          btn.addEventListener('click', function () {
            reassign(btn.getAttribute('data-id'), btn.getAttribute('data-newid'));
          });
        });
        statusResults.querySelectorAll('.cancel-btn').forEach(function (btn) {
          btn.addEventListener('click', function () { cancel(btn.getAttribute('data-id')); });
        });

        statusResults.hidden = false;
      });
    }

    function runSearch() {
      if (!lastSearch) return;
      var url = '/api/queue/lookup?phone=' + encodeURIComponent(lastSearch.phone) +
        (lastSearch.name ? '&name=' + encodeURIComponent(lastSearch.name) : '');

      api(url).then(function (res) {
        renderResults(res.ok ? res.data : []);
      });
    }

    statusForm.addEventListener('submit', function (e) {
      e.preventDefault();
      var phone = document.getElementById('statusPhone').value.trim();
      var name = document.getElementById('statusName').value.trim();
      if (!phone) return;

      lastSearch = { phone: phone, name: name };
      runSearch();
      statusResults.scrollIntoView({ behavior: 'smooth', block: 'center' });

      if (pollTimer) clearInterval(pollTimer);
      pollTimer = setInterval(runSearch, STATUS_POLL_MS);
    });
  }

});