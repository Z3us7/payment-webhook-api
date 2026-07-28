
const RAZORPAY_KEY = 'rzp_test_TFO0JGyqfvuSSY'; // ← your Razorpay test key ID

const ORDER_ENDPOINT = 'https://payment-webhook-api-kp40.onrender.com/api/v1/orders'; // Note: Ensure this URL exactly matches your Render deployment URL


// ─── DOM refs ───
const statusPill = document.getElementById('statusPill');
const statusText = document.getElementById('statusText');
const payBtn = document.getElementById('payBtn');
const terminalOutput = document.getElementById('terminalContent');
const amountInput = document.getElementById('amountInput');
const dbTbody = document.getElementById('dbTbody');

// ─── Mock Database ───
let dbRecords = [];
let nextDbId = 1001;

function renderDb() {
  dbTbody.innerHTML = '';
  // Show latest first
  [...dbRecords].reverse().forEach(record => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>#${record.id}</td>
      <td>₹${record.amount}</td>
      <td><span class="db-status ${record.status.toLowerCase()}">${record.status}</span></td>
      <td>${record.rzp_order}</td>
    `;
    dbTbody.appendChild(tr);
  });
}

function insertDbRecord(amount, rzp_order) {
  dbRecords.push({ id: nextDbId++, amount, status: 'CREATED', rzp_order });
  renderDb();
  logTerminal(`[DB] Inserted order #${nextDbId - 1}`, 'sys');
}

function updateDbRecord(rzp_order, newStatus) {
  const record = dbRecords.find(r => r.rzp_order === rzp_order);
  if (record) {
    record.status = newStatus;
    renderDb();
    logTerminal(`[DB] Updated order ${rzp_order} to ${newStatus}`, 'sys');
  }
}

// ─── Terminal Logger ───
function logTerminal(message, type = 'sys') {
  const time = new Date().toLocaleTimeString('en-US', { hour12: false });
  const line = document.createElement('div');
  line.className = `log-line ${type}`;
  line.textContent = `[${time}] ${message}`;
  terminalOutput.appendChild(line);
  terminalOutput.scrollTop = terminalOutput.scrollHeight;
}

// ─── Status state machine ───
function setStatus(state, label) {
  statusPill.className = 'status-pill' + (state ? ' ' + state : '');
  statusText.textContent = label;
}

// ─── Button handler (async — fetches order ID from backend first) ───
payBtn.addEventListener('click', async function () {
  const amount = parseInt(amountInput.value, 10);
  if (isNaN(amount) || amount <= 0) {
    alert("Please enter a valid amount");
    return;
  }

  payBtn.disabled = true;
  setStatus('is-generating', 'GENERATING ORDER...');

  logTerminal(`Initiating payment for ₹${amount}...`, 'sys');
  logTerminal(`POST ${ORDER_ENDPOINT}`, 'req');

  let orderId;
  try {
    const res = await fetch(ORDER_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        productName: 'Test Product',
        amount: amount,
        currency: 'INR'
      })
    });

    if (!res.ok) {
      const errText = await res.text();
      throw new Error(`HTTP ${res.status}: ${errText}`);
    }

    const jsonResponse = await res.json();
    orderId = jsonResponse.data.orderId; // extract orderId from JSend format
    logTerminal(`Received order_id: ${orderId}`, 'res');

    // Simulate backend writing to DB
    insertDbRecord(amount, orderId);

  } catch (err) {
    console.error(err);
    setStatus('is-error', 'ORDER FAILED');
    logTerminal(`Failed to generate order: ${err.message}`, 'err');
    payBtn.disabled = false;
    return;
  }

  setStatus('', 'STANDBY');
  logTerminal('Opening Razorpay checkout modal...', 'rzp');

  const rzp = new Razorpay({
    key: RAZORPAY_KEY,
    amount: amount * 100,   // paise — must match the order amount
    currency: 'INR',
    name: 'Webhook Test',
    description: 'Spring Boot Integration Test',
    order_id: orderId,
    theme: { color: '#d4af37' }, // Olympian Gold

    handler: async function (response) {
      logTerminal(`Payment Success! ID: ${response.razorpay_payment_id}`, 'res');
      setStatus('is-syncing', 'SYNCING WEBHOOK...');

      // ponytail: fixed 2.2s grace period — assumes Razorpay test-mode webhook arrives within ~2s.
      // Upgrade path: poll GET /api/payments/{orderId}/status and flip on COMPLETED.
      setTimeout(function () {
        setStatus('is-success', '200 OK');
        logTerminal('Webhook sync assumed completed.', 'sys');
        // Update mock DB to PAID
        updateDbRecord(orderId, 'PAID');

        // Reset the UI after 3 seconds so user can make another payment
        setTimeout(function () {
          payBtn.disabled = false;
          setStatus('', 'STANDBY');
          logTerminal('System ready for next payment.', 'sys');
        }, 3000);

      }, 2200);
    },

    modal: {
      ondismiss: function () {
        payBtn.disabled = false;
        setStatus('', 'STANDBY');
        logTerminal('Razorpay modal dismissed by user.', 'rzp');
      }
    }
  });

  rzp.on('payment.failed', function (response) {
    setStatus('is-error', 'FAILED');
    payBtn.disabled = false;
    logTerminal(`Payment failed: ${response.error.description}`, 'err');
  });

  rzp.open();
});
