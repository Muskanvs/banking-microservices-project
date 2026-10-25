
import React, { useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Activity,
  ArrowDownLeft,
  ArrowLeftRight,
  ArrowUpRight,
  Banknote,
  CheckCircle2,
  ChevronRight,
  CircleHelp,
  Clock3,
  CreditCard,
  Fingerprint,
  History,
  LayoutDashboard,
  LifeBuoy,
  Menu,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Wallet,
  XCircle
} from 'lucide-react';
import './styles.css';

const initialBase =
  import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

const money = (value) =>
  new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 2
  }).format(Number(value || 0));

const prettyDate = (value) =>
  value
    ? new Date(value).toLocaleString('en-IN', {
        dateStyle: 'medium',
        timeStyle: 'short'
      })
    : '—';

function App() {
  const [apiBase, setApiBase] = useState(initialBase);
  const [section, setSection] = useState('Overview');
  const [accountNumber, setAccountNumber] = useState('');
  const [account, setAccount] = useState(null);
  const [balance, setBalance] = useState(null);
  const [history, setHistory] = useState([]);
  const [transactionId, setTransactionId] = useState('');
  const [transaction, setTransaction] = useState(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  const [createForm, setCreateForm] = useState({
    accountHolderName: '',
    email: '',
    phone: '',
    accountType: 'SAVINGS',
    initialDeposit: '1000'
  });

  const [transferForm, setTransferForm] = useState({
    senderAccountNumber: '',
    receiverAccountNumber: '',
    amount: '',
    description: 'Personal transfer'
  });

  const [paymentForm, setPaymentForm] = useState({
    accountNumber: '',
    amount: '',
    description: 'Add funds'
  });

  const [otp, setOtp] = useState('');
  const [showApi, setShowApi] = useState(false);

  const callApi = async (path, options = {}) => {
    const base = apiBase.trim().replace(/\/$/, '');

    const response = await fetch(`${base}${path}`, {
      ...options,
      headers: {
        ...(options.body
          ? { 'Content-Type': 'application/json' }
          : {}),
        ...(options.headers || {})
      }
    });

    const text = await response.text();
    let body;

    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = text;
    }

    if (!response.ok) {
      const message =
        typeof body === 'string'
          ? body
          : body?.message ||
            body?.error ||
            body?.detail ||
            JSON.stringify(body);

      throw new Error(
        `${response.status} ${response.statusText}${
          message ? ` — ${message}` : ''
        }`
      );
    }

    return body;
  };

  const run = async (label, fn) => {
    setBusy(true);
    setNotice(null);

    try {
      const result = await fn();

      if (result?.message) {
        setNotice({ type: 'success', text: result.message });
      } else {
        setNotice({
          type: 'success',
          text: `${label} completed successfully.`
        });
      }

      return result;
    } catch (e) {
      setNotice({
        type: 'error',
        text: e.message || 'Request failed.'
      });

      return null;
    } finally {
      setBusy(false);
    }
  };

  const loadAccount = async (number = accountNumber) =>
    run('Account lookup', async () => {
      if (!number.trim()) {
        throw new Error('Enter an account number first.');
      }

      const encodedNumber = encodeURIComponent(number.trim());

      const [a, b, h] = await Promise.all([
        callApi(`/api/v1/accounts/${encodedNumber}`),
        callApi(`/api/v1/accounts/${encodedNumber}/balance`),
        callApi(
          `/api/v1/transactions/account/${encodedNumber}`
        ).catch(() => [])
      ]);

      setAccount(a);
      setBalance(
        typeof b === 'number' || typeof b === 'string'
          ? b
          : b?.balance
      );
      setHistory(Array.isArray(h) ? h : []);
      setAccountNumber(number.trim());

      return { message: 'Account details refreshed.' };
    });

  const loadHistory = async () =>
    run('Transaction history', async () => {
      if (!accountNumber.trim()) {
        throw new Error(
          'Look up an account first or enter its number.'
        );
      }

      const h = await callApi(
        `/api/v1/transactions/account/${encodeURIComponent(
          accountNumber.trim()
        )}`
      );

      setHistory(Array.isArray(h) ? h : []);

      return { message: 'Transaction history refreshed.' };
    });

  const findTransaction = async (id = transactionId) =>
    run('Transaction lookup', async () => {
      if (!id.trim()) {
        throw new Error('Enter a transaction ID.');
      }

      const t = await callApi(
        `/api/v1/transactions/${encodeURIComponent(id.trim())}`
      );

      setTransaction(t);
      setTransactionId(id.trim());

      return { message: 'Transaction details loaded.' };
    });

  const createAccount = async (e) => {
    e.preventDefault();

    const result = await run('Account creation', async () => {
      const data = await callApi('/api/v1/accounts', {
        method: 'POST',
        body: JSON.stringify({
          ...createForm,
          initialDeposit: Number(createForm.initialDeposit)
        })
      });

      setAccount(data);
      setAccountNumber(data.accountNumber || '');
      setBalance(data.balance);
      setHistory([]);
      setSection('Overview');

      return {
        message: `Account created. Account number: ${data.accountNumber}`
      };
    });

    if (result) {
      setCreateForm({
        accountHolderName: '',
        email: '',
        phone: '',
        accountType: 'SAVINGS',
        initialDeposit: '1000'
      });
    }
  };

  const transfer = async (e) => {
    e.preventDefault();

    const result = await run('Transfer request', async () => {
      const t = await callApi('/api/v1/transactions/transfer', {
        method: 'POST',
        body: JSON.stringify({
          ...transferForm,
          amount: Number(transferForm.amount)
        })
      });

      setTransaction(t);
      setTransactionId(t.id || '');
      setSection('Transactions');

      if (transferForm.senderAccountNumber) {
        setAccountNumber(transferForm.senderAccountNumber);
      }

      return {
        message: `Transfer request submitted. Current status: ${
          t.status || 'unknown'
        }. Follow the transaction status and verify OTP if required.`
      };
    });

    if (result) {
      setTransferForm({
        senderAccountNumber: '',
        receiverAccountNumber: '',
        amount: '',
        description: 'Personal transfer'
      });
    }
  };

  // Create the backend order, then open Razorpay Checkout.
  const createPaymentOrder = async (e) => {
    e.preventDefault();

    await run('Payment order creation', async () => {
      const amountInRupees = Number(paymentForm.amount);

      if (
        !paymentForm.accountNumber.trim() ||
        !Number.isFinite(amountInRupees) ||
        amountInRupees <= 0
      ) {
        throw new Error(
          'Enter a valid account number and payment amount.'
        );
      }

      // Step 1: Ask the backend to create a Razorpay order.
      const p = await callApi('/api/v1/payments/create-order', {
        method: 'POST',
        body: JSON.stringify({
          ...paymentForm,
          accountNumber: paymentForm.accountNumber.trim(),
          amount: amountInRupees
        })
      });

      // Step 2: Check that the backend returned the required fields.
      if (!p?.razorpayOrderId || !p?.razorpayKeyId) {
        throw new Error(
          'The backend did not return razorpayOrderId or razorpayKeyId. Check the API response.'
        );
      }

      // Step 3: Check that Checkout was loaded in index.html.
      if (typeof window.Razorpay !== 'function') {
        throw new Error(
          'Razorpay Checkout is not loaded. Add the Razorpay script to index.html and reload the page.'
        );
      }

      // Step 4: Configure the Checkout window.
      const options = {
        key: p.razorpayKeyId,
        order_id: p.razorpayOrderId,
        amount: Math.round(amountInRupees * 100),
        currency: p.currency || 'INR',
        name: 'Northstar Demo Banking',
        description: paymentForm.description || 'Add funds',

        handler: function (response) {
          console.log(
            'Razorpay Checkout response:',
            response
          );

          setNotice({
            type: 'success',
            text:
              'Checkout returned a response for payment ' +
              response.razorpay_payment_id +
              '. The backend must still verify the payment signature before confirming payment.'
          });
        },

        modal: {
          ondismiss: function () {
            setNotice({
              type: 'error',
              text:
                'Razorpay Checkout was closed. Check the payment status before retrying.'
            });
          }
        },

        theme: {
          color: '#2563eb'
        }
      };

      // Step 5: Open Razorpay Checkout.
      const checkout = new window.Razorpay(options);
      checkout.open();

      return {
        message:
          `Razorpay Checkout opened for order ${p.razorpayOrderId}. ` +
          'Complete a test payment to continue.'
      };
    });
  };

  const verifyOtp = async (e) => {
    e.preventDefault();

    const t = await run('OTP verification', async () => {
      if (!transactionId.trim() || !otp.trim()) {
        throw new Error('Enter a transaction ID and OTP.');
      }

      return await callApi(
        `/api/v1/transactions/${encodeURIComponent(
          transactionId.trim()
        )}/verify?otp=${encodeURIComponent(otp.trim())}`,
        { method: 'POST' }
      );
    });

    if (t) {
      setTransaction(t);
      setOtp('');
      setNotice({
        type: 'success',
        text: `OTP verification request completed. Transaction status: ${
          t.status || 'unknown'
        }.`
      });
    }
  };

  const navItems = [
    { name: 'Overview', icon: LayoutDashboard },
    { name: 'Accounts', icon: Wallet },
    { name: 'Transfer', icon: ArrowLeftRight },
    { name: 'Transactions', icon: History },
    { name: 'Payments', icon: CreditCard }
  ];

  // UPDATED: Safely calculate the number of completed transactions.
  const completedCount = useMemo(
    () =>
      (Array.isArray(history) ? history : []).filter(
        (t) => String(t?.status || '').toUpperCase() === 'COMPLETED'
      ).length,
    [history]
  );

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <LandmarkIcon />
          </div>
          <div>
            <strong>northstar</strong>
            <span>DEMO BANKING</span>
          </div>
        </div>

        <div className="workspace-label">WORKSPACE</div>

        <nav>
          {navItems.map(({ name, icon: Icon }) => (
            <button
              key={name}
              className={`nav-item ${
                section === name ? 'active' : ''
              }`}
              onClick={() => {
                setSection(name);
                setNotice(null);
              }}
            >
              <Icon size={18} />
              <span>{name}</span>
              {section === name && (
                <ChevronRight size={16} className="nav-chevron" />
              )}
            </button>
          ))}
        </nav>

        <div className="sidebar-bottom">
          <div className="security-card">
            <div className="security-icon">
              <ShieldCheck size={18} />
            </div>
            <strong>Security first</strong>
            <p>
              Demo environment. Use test data only; never enter
              real banking credentials.
            </p>
          </div>

          <button
            className="nav-item"
            onClick={() => setShowApi((v) => !v)}
          >
            <Activity size={18} />
            <span>API connection</span>
            <span className={`status-dot ${apiBase ? 'on' : ''}`} />
          </button>

          <div className="sidebar-foot">
            <span className="tiny-dot" /> Microservices demo <span>v1.0</span>
          </div>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="crumb">
            <span>Workspace</span>
            <ChevronRight size={14} />
            <strong>{section}</strong>
          </div>
          <div className="top-actions">
            <div className="environment">
              <span className="tiny-dot" />
              {apiBase.includes('localhost')
                ? 'Local environment'
                : 'Cloud environment'}
            </div>
            <div className="avatar">NS</div>
          </div>
        </header>

        <div className="content">
          <div className="welcome-row">
            <div>
              <div className="eyebrow">
                PERSONAL BANKING · MICROSERVICES
              </div>
              <h1>
                {section === 'Overview' ? 'Your money, in view.' : section}
              </h1>
              <p className="subhead">
                A connected banking experience powered by your Spring Boot services.
              </p>
            </div>
            <button
              className="btn btn-light"
              onClick={() => {
                if (accountNumber) {
                  loadAccount(accountNumber);
                } else {
                  setShowApi((v) => !v);
                }
              }}
              disabled={busy}
            >
              <RefreshCw size={16} /> Refresh data
            </button>
          </div>

          {showApi && (
            <div className="api-config panel">
              <div>
                <strong>Backend API base URL</strong>
                <p>Requests go through your API Gateway, not directly to each service.</p>
              </div>
              <div className="api-input-row">
                <input
                  value={apiBase}
                  onChange={(e) => setApiBase(e.target.value)}
                  placeholder="http://localhost:8080"
                />
                <button
                  className="btn btn-primary"
                  onClick={() => {
                    setNotice({
                      type: 'success',
                      text: `API base URL set to ${apiBase}`
                    });
                    setShowApi(false);
                  }}
                >
                  Save URL
                </button>
              </div>
              <small>
                For deployment, set <code>VITE_API_BASE_URL</code> to your public Gateway URL and rebuild the frontend.
              </small>
            </div>
          )}

          {notice && (
            <div className={`notice ${notice.type}`}>
              <span>
                {notice.type === 'success' ? (
                  <CheckCircle2 size={18} />
                ) : (
                  <XCircle size={18} />
                )}
              </span>
              <div>{notice.text}</div>
              <button
                aria-label="Dismiss"
                onClick={() => setNotice(null)}
              >
                <XCircle size={16} />
              </button>
            </div>
          )}

          {section === 'Overview' && (
            <>
              <div className="hero-card">
                <div className="hero-copy">
                  <div className="hero-pill">
                    <span className="tiny-dot" /> YOUR BANKING OVERVIEW
                  </div>
                  <h2>
                    Simple banking.<br />
                    <em>Smarter connections.</em>
                  </h2>
                  <p>
                    Manage account details, explore transfers and track transaction activity from one place.
                  </p>
                  <button
                    className="btn btn-white"
                    onClick={() => setSection('Accounts')}
                  >
                    Explore accounts <ChevronRight size={16} />
                  </button>
                </div>
                <div className="hero-art">
                  <div className="orb orb-one" />
                  <div className="orb orb-two" />
                  <div className="hero-card-visual">
                    <div className="visual-top">
                      <span>northstar</span>
                      <span className="chip" />
                    </div>
                    <div className="visual-number">
                      •••• &nbsp; •••• &nbsp; •••• &nbsp; 2048
                    </div>
                    <div className="visual-bottom">
                      <span>DEMO ACCOUNT</span>
                      <span>VISA</span>
                    </div>
                  </div>
                  <div className="floating-badge">
                    <div className="floating-icon">
                      <ShieldCheck size={18} />
                    </div>
                    <div>
                      <strong>Connected services</strong>
                      <span>Gateway · Accounts · Transfers</span>
                    </div>
                  </div>
                </div>
              </div>

              <div className="section-heading">
                <div>
                  <h3>Account snapshot</h3>
                  <p>Look up an account to load live backend data.</p>
                </div>
                <button
                  className="text-button"
                  onClick={() => setSection('Accounts')}
                >
                  Manage accounts <ChevronRight size={15} />
                </button>
              </div>

              <div className="stats-grid">
                <div className="stat-card balance-stat">
                  <div className="stat-top">
                    <span>Available balance</span>
                    <span className="stat-icon"><Wallet size={18} /></span>
                  </div>
                  <div className="stat-value">
                    {balance !== null && balance !== undefined
                      ? money(balance)
                      : '— — —'}
                  </div>
                  <div className="stat-foot">
                    {account
                      ? `Account ending ${String(account.accountNumber).slice(-4)}`
                      : 'No account selected'}
                  </div>
                </div>

                <div className="stat-card">
                  <div className="stat-top">
                    <span>Account status</span>
                    <span className="stat-icon green"><ShieldCheck size={18} /></span>
                  </div>
                  <div className="stat-value status-value">
                    {account?.status || 'Not loaded'}
                  </div>
                  <div className="stat-foot">
                    {account?.accountType || 'Look up an account to view status'}
                  </div>
                </div>

                <div className="stat-card">
                  <div className="stat-top">
                    <span>Transactions loaded</span>
                    <span className="stat-icon purple"><Activity size={18} /></span>
                  </div>
                  <div className="stat-value">
                    {Array.isArray(history) ? history.length : 0}
                  </div>
                  <div className="stat-foot">
                    {completedCount} completed in this history
                  </div>
                </div>
              </div>

              <div className="lower-grid">
                <div className="panel account-lookup">
                  <div className="panel-heading">
                    <div>
                      <h3>Quick account lookup</h3>
                      <p>Retrieve a real account from Account Service.</p>
                    </div>
                    <div className="panel-heading-icon"><Search size={18} /></div>
                  </div>
                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      loadAccount();
                    }}
                  >
                    <label>Account number</label>
                    <div className="input-with-button">
                      <input
                        value={accountNumber}
                        onChange={(e) => setAccountNumber(e.target.value)}
                        placeholder="Enter 12-digit account number"
                      />
                      <button className="btn btn-primary" disabled={busy}>
                        <Search size={15} /> Find
                      </button>
                    </div>
                  </form>

                  {account && (
                    <div className="account-mini">
                      <div className="person-avatar">
                        {(account.accountHolderName || 'A').slice(0, 1).toUpperCase()}
                      </div>
                      <div className="person-data">
                        <strong>{account.accountHolderName}</strong>
                        <span>{account.email}</span>
                      </div>
                      <span className={`tag ${String(account.status).toLowerCase()}`}>
                        {account.status}
                      </span>
                    </div>
                  )}
                </div>

                <div className="panel quick-actions">
                  <div className="panel-heading">
                    <div>
                      <h3>Quick actions</h3>
                      <p>Call the matching backend endpoint.</p>
                    </div>
                  </div>
                  <button className="action-row" onClick={() => setSection('Accounts')}>
                    <div className="action-icon teal"><Plus size={18} /></div>
                    <div>
                      <strong>Open a new account</strong>
                      <span>Create an account with an initial deposit</span>
                    </div>
                    <ChevronRight size={17} />
                  </button>
                  <button className="action-row" onClick={() => setSection('Transfer')}>
                    <div className="action-icon blue"><ArrowUpRight size={18} /></div>
                    <div>
                      <strong>Transfer money</strong>
                      <span>Start a transfer between accounts</span>
                    </div>
                    <ChevronRight size={17} />
                  </button>
                  <button className="action-row" onClick={() => setSection('Transactions')}>
                    <div className="action-icon orange"><History size={18} /></div>
                    <div>
                      <strong>View activity</strong>
                      <span>Review history and verify a transaction</span>
                    </div>
                    <ChevronRight size={17} />
                  </button>
                </div>
              </div>

              <TransactionTable
                history={history}
                onSelect={(t) => {
                  setTransaction(t);
                  setTransactionId(t.id || '');
                  setSection('Transactions');
                }}
                onAll={() => setSection('Transactions')}
              />
            </>
          )}

          {section === 'Accounts' && (
            <div className="forms-grid">
              <div className="panel form-panel">
                <div className="panel-heading">
                  <div>
                    <h3>Create an account</h3>
                    <p>Uses POST /api/v1/accounts.</p>
                  </div>
                  <div className="panel-heading-icon"><Plus size={18} /></div>
                </div>
                <form className="form-stack" onSubmit={createAccount}>
                  <label>
                    Account holder name
                    <input
                      required
                      value={createForm.accountHolderName}
                      onChange={(e) => setCreateForm({ ...createForm, accountHolderName: e.target.value })}
                      placeholder="Full name"
                    />
                  </label>
                  <label>
                    Email address
                    <input
                      required
                      type="email"
                      value={createForm.email}
                      onChange={(e) => setCreateForm({ ...createForm, email: e.target.value })}
                      placeholder="you@example.com"
                    />
                  </label>
                  <label>
                    Phone number
                    <input
                      required
                      value={createForm.phone}
                      onChange={(e) => setCreateForm({ ...createForm, phone: e.target.value })}
                      placeholder="Phone number"
                    />
                  </label>
                  <div className="two-fields">
                    <label>
                      Account type
                      <select
                        value={createForm.accountType}
                        onChange={(e) => setCreateForm({ ...createForm, accountType: e.target.value })}
                      >
                        <option value="SAVINGS">Savings</option>
                        <option value="CURRENT">Current</option>
                        <option value="FIXED_DEPOSIT">Fixed deposit</option>
                      </select>
                    </label>
                    <label>
                      Initial deposit (₹)
                      <input
                        required
                        type="number"
                        min="0.01"
                        step="0.01"
                        value={createForm.initialDeposit}
                        onChange={(e) => setCreateForm({ ...createForm, initialDeposit: e.target.value })}
                      />
                    </label>
                  </div>
                  <button className="btn btn-primary btn-full" disabled={busy}>
                    Create account <ChevronRight size={16} />
                  </button>
                </form>
                <p className="form-note">
                  <ShieldCheck size={15} /> Use test data only. The backend validates required fields and email format.
                </p>
              </div>

              <div className="panel form-panel">
                <div className="panel-heading">
                  <div>
                    <h3>Find an existing account</h3>
                    <p>Uses GET account and balance endpoints.</p>
                  </div>
                  <div className="panel-heading-icon"><Search size={18} /></div>
                </div>
                <form
                  className="form-stack"
                  onSubmit={(e) => {
                    e.preventDefault();
                    loadAccount();
                  }}
                >
                  <label>
                    Account number
                    <input
                      required
                      value={accountNumber}
                      onChange={(e) => setAccountNumber(e.target.value)}
                      placeholder="Enter account number"
                    />
                  </label>
                  <button className="btn btn-primary btn-full" disabled={busy}>
                    Retrieve account <Search size={16} />
                  </button>
                </form>

                {account && (
                  <div className="account-detail">
                    <div className="detail-line"><span>Account holder</span><strong>{account.accountHolderName}</strong></div>
                    <div className="detail-line"><span>Account number</span><strong className="mono">{account.accountNumber}</strong></div>
                    <div className="detail-line"><span>Account type</span><strong>{account.accountType}</strong></div>
                    <div className="detail-line"><span>Status</span><strong><span className={`tag ${String(account.status).toLowerCase()}`}>{account.status}</span></strong></div>
                    <div className="detail-line"><span>Available balance</span><strong className="money-text">{money(balance ?? account.balance)}</strong></div>
                    <div className="detail-line"><span>Daily transaction limit</span><strong>{money(account.dailyTransactionLimit)}</strong></div>
                    <div className="detail-line"><span>Created</span><strong>{prettyDate(account.createdAt)}</strong></div>
                  </div>
                )}
              </div>
            </div>
          )}

          {section === 'Transfer' && (
            <div className="forms-grid">
              <div className="panel form-panel">
                <div className="panel-heading">
                  <div>
                    <h3>Transfer between accounts</h3>
                    <p>Starts your backend transfer workflow.</p>
                  </div>
                  <div className="panel-heading-icon"><ArrowLeftRight size={18} /></div>
                </div>
                <form className="form-stack" onSubmit={transfer}>
                  <label>
                    From account
                    <input
                      required
                      value={transferForm.senderAccountNumber}
                      onChange={(e) => setTransferForm({ ...transferForm, senderAccountNumber: e.target.value })}
                      placeholder="Sender account number"
                    />
                  </label>
                  <label>
                    To account
                    <input
                      required
                      value={transferForm.receiverAccountNumber}
                      onChange={(e) => setTransferForm({ ...transferForm, receiverAccountNumber: e.target.value })}
                      placeholder="Receiver account number"
                    />
                  </label>
                  <label>
                    Amount (₹)
                    <input
                      required
                      type="number"
                      min="0.01"
                      step="0.01"
                      value={transferForm.amount}
                      onChange={(e) => setTransferForm({ ...transferForm, amount: e.target.value })}
                      placeholder="0.00"
                    />
                  </label>
                  <label>
                    Description (optional)
                    <input
                      value={transferForm.description}
                      onChange={(e) => setTransferForm({ ...transferForm, description: e.target.value })}
                      placeholder="Reason for transfer"
                    />
                  </label>
                  <button className="btn btn-primary btn-full" disabled={busy}>
                    Submit transfer <ArrowUpRight size={16} />
                  </button>
                </form>
                <div className="warning-note">
                  <CircleHelp size={16} />
                  <p>
                    Transfer requests may start as <b>PROCESSING</b> and require OTP verification/fraud checks. Do not treat submission as proof the transfer completed.
                  </p>
                </div>
              </div>

              <div className="panel info-panel">
                <div className="panel-heading">
                  <div>
                    <h3>How transfers work</h3>
                    <p>Based on the current service implementation.</p>
                  </div>
                </div>
                <div className="flow-steps">
                  <div className="flow-step">
                    <span>01</span>
                    <div>
                      <strong>Request submitted</strong>
                      <p>Frontend sends sender, receiver, amount and description to Transaction Service through Gateway.</p>
                    </div>
                  </div>
                  <div className="flow-step">
                    <span>02</span>
                    <div>
                      <strong>Sender balance deducted</strong>
                      <p>Transaction Service calls Account Service to deduct funds and records the transaction.</p>
                    </div>
                  </div>
                  <div className="flow-step">
                    <span>03</span>
                    <div>
                      <strong>Fraud check event</strong>
                      <p>Kafka event is published for fraud detection and related processing.</p>
                    </div>
                  </div>
                  <div className="flow-step">
                    <span>04</span>
                    <div>
                      <strong>Verify current status</strong>
                      <p>Use transaction ID to reload status. Submit OTP if your flow has generated one.</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {section === 'Transactions' && (
            <>
              <div className="forms-grid">
                <div className="panel form-panel">
                  <div className="panel-heading">
                    <div>
                      <h3>Transaction details</h3>
                      <p>Uses GET /api/v1/transactions/{'{transactionId}'}.</p>
                    </div>
                    <div className="panel-heading-icon"><Search size={18} /></div>
                  </div>
                  <form
                    className="form-stack"
                    onSubmit={(e) => {
                      e.preventDefault();
                      findTransaction();
                    }}
                  >
                    <label>
                      Transaction ID
                      <input
                        value={transactionId}
                        onChange={(e) => setTransactionId(e.target.value)}
                        placeholder="Paste transaction ID"
                        required
                      />
                    </label>
                    <button className="btn btn-primary btn-full" disabled={busy}>
                      Find transaction <Search size={16} />
                    </button>
                  </form>
                  {transaction && <TransactionDetails transaction={transaction} />}
                </div>

                <div className="panel form-panel">
                  <div className="panel-heading">
                    <div>
                      <h3>Verify transaction OTP</h3>
                      <p>Uses POST /{'{transactionId}'}/verify?otp=…</p>
                    </div>
                    <div className="panel-heading-icon"><Fingerprint size={18} /></div>
                  </div>
                  <form className="form-stack" onSubmit={verifyOtp}>
                    <label>
                      Transaction ID
                      <input
                        required
                        value={transactionId}
                        onChange={(e) => setTransactionId(e.target.value)}
                        placeholder="Transaction ID"
                      />
                    </label>
                    <label>
                      OTP
                      <input
                        required
                        value={otp}
                        onChange={(e) => setOtp(e.target.value)}
                        placeholder="Enter OTP from your backend flow"
                      />
                    </label>
                    <button className="btn btn-primary btn-full" disabled={busy}>
                      Verify OTP <ShieldCheck size={16} />
                    </button>
                  </form>
                  <p className="form-note">
                    <CircleHelp size={15} /> The UI does not invent an OTP. Use the OTP generated by your backend and check its logs/notification flow.
                  </p>
                </div>
              </div>

              <TransactionTable
                history={history}
                onSelect={(t) => {
                  setTransaction(t);
                  setTransactionId(t.id || '');
                }}
                onAll={() => {}}
              />

              <div className="panel history-tools">
                <div>
                  <h3>Load account history</h3>
                  <p>Enter an account number on the Accounts tab or use the current lookup value.</p>
                </div>
                <button className="btn btn-light" onClick={loadHistory} disabled={busy}>
                  <RefreshCw size={15} /> Refresh history
                </button>
              </div>
            </>
          )}

          {section === 'Payments' && (
            <div className="forms-grid">
              <div className="panel form-panel">
                <div className="panel-heading">
                  <div>
                    <h3>Create a payment order</h3>
                    <p>Uses POST /api/v1/payments/create-order.</p>
                  </div>
                  <div className="panel-heading-icon"><CreditCard size={18} /></div>
                </div>

                <form className="form-stack" onSubmit={createPaymentOrder}>
                  <label>
                    Account number
                    <input
                      required
                      value={paymentForm.accountNumber}
                      onChange={(e) => setPaymentForm({
                        ...paymentForm,
                        accountNumber: e.target.value
                      })}
                      placeholder="Account number"
                    />
                  </label>

                  <label>
                    Amount (₹)
                    <input
                      required
                      type="number"
                      min="0.01"
                      step="0.01"
                      value={paymentForm.amount}
                      onChange={(e) => setPaymentForm({
                        ...paymentForm,
                        amount: e.target.value
                      })}
                      placeholder="0.00"
                    />
                  </label>

                  <label>
                    Description (optional)
                    <input
                      value={paymentForm.description}
                      onChange={(e) => setPaymentForm({
                        ...paymentForm,
                        description: e.target.value
                      })}
                      placeholder="Payment description"
                    />
                  </label>

                  <button className="btn btn-primary btn-full" disabled={busy}>
                    Create Razorpay order <ChevronRight size={16} />
                  </button>
                </form>

                <div className="warning-note">
                  <CircleHelp size={16} />
                  <p>
                    Checkout is connected to the order creation response.
                    Payment signature verification and webhook confirmation
                    still need to be implemented on the backend.
                  </p>
                </div>
              </div>

              <div className="panel info-panel">
                <div className="panel-heading">
                  <div>
                    <h3>Payment integration status</h3>
                    <p>What this screen can and cannot do.</p>
                  </div>
                </div>

                <div className="status-list">
                  <div>
                    <span className="status-check"><CheckCircle2 size={17} /></span>
                    <div>
                      <strong>Create order request</strong>
                      <p>Supported by the current Payment Controller.</p>
                    </div>
                  </div>

                  <div>
                    <span className="status-check"><CheckCircle2 size={17} /></span>
                    <div>
                      <strong>Open Checkout</strong>
                      <p>Uses the Razorpay order ID and public Key ID returned by your backend.</p>
                    </div>
                  </div>

                  <div>
                    <span className="status-pending"><Clock3 size={17} /></span>
                    <div>
                      <strong>Backend signature verification</strong>
                      <p>Must be implemented before treating a payment as confirmed.</p>
                    </div>
                  </div>

                  <div>
                    <span className="status-pending"><Clock3 size={17} /></span>
                    <div>
                      <strong>Webhook confirmation</strong>
                      <p>Requires a webhook endpoint and Razorpay Dashboard configuration.</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          <footer className="footer">
            <span>Northstar Banking · Portfolio demo</span>
            <span><ShieldCheck size={14} /> No real money or real customer data</span>
          </footer>
        </div>
      </main>
    </div>
  );
}

// UPDATED: Safe handling of missing or non-array transaction history.
function TransactionTable({ history, onSelect, onAll }) {
  const safeHistory = Array.isArray(history) ? history : [];

  return (
    <div className="panel table-panel">
      <div className="panel-heading">
        <div>
          <h3>Recent transactions</h3>
          <p>Live records loaded from Transaction Service.</p>
        </div>
        <button className="text-button" onClick={onAll}>
          View transactions <ChevronRight size={15} />
        </button>
      </div>

      {!safeHistory.length ? (
        <div className="empty-state">
          <div className="empty-icon">
            <History size={22} />
          </div>
          <strong>No transaction history loaded</strong>
          <p>
            Look up an account to fetch its transaction history.
            No sample records are displayed.
          </p>
        </div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Transaction</th>
                <th>Type</th>
                <th>Date</th>
                <th>Amount</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {safeHistory.map((t, i) => (
                <tr
                  key={t?.id || i}
                  onClick={() => onSelect(t)}
                  className="clickable-row"
                >
                  <td>
                    <strong className="mono">
                      {String(t?.id || '—').slice(0, 13)}
                    </strong>
                    <span className="cell-sub">
                      {t?.description ||
                        t?.referenceNumber ||
                        'No description'}
                    </span>
                  </td>
                  <td>{t?.type || '—'}</td>
                  <td>{prettyDate(t?.createdAt)}</td>
                  <td className="amount-cell">
                    {money(t?.amount)}
                  </td>
                  <td>
                    <span
                      className={`tag ${String(
                        t?.status || ''
                      ).toLowerCase()}`}
                    >
                      {t?.status || 'Unknown'}
                    </span>
                  </td>
                  <td>
                    <ChevronRight size={16} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function TransactionDetails({ transaction: t }) {
  return (
    <div className="account-detail transaction-detail">
      <div className="detail-line">
        <span>Status</span>
        <strong>
          <span className={`tag ${String(t.status).toLowerCase()}`}>
            {t.status}
          </span>
        </strong>
      </div>
      <div className="detail-line">
        <span>Transaction ID</span>
        <strong className="mono">{t.id}</strong>
      </div>
      <div className="detail-line">
        <span>Amount</span>
        <strong className="money-text">{money(t.amount)}</strong>
      </div>
      <div className="detail-line">
        <span>Sender</span>
        <strong className="mono">{t.senderAccountNumber || '—'}</strong>
      </div>
      <div className="detail-line">
        <span>Receiver</span>
        <strong className="mono">{t.receiverAccountNumber || '—'}</strong>
      </div>
      <div className="detail-line">
        <span>Failure reason</span>
        <strong>{t.failureReason || '—'}</strong>
      </div>
      <div className="detail-line">
        <span>Created</span>
        <strong>{prettyDate(t.createdAt)}</strong>
      </div>
    </div>
  );
}

function LandmarkIcon() {
  return <Banknote size={22} />;
}

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
