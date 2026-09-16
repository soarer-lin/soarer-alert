(function () {
    'use strict';

    const form = document.getElementById('loginForm');
    const usernameInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');
    const button = document.getElementById('loginButton');
    const errorBox = document.getElementById('loginError');
    const loginModeHint = document.getElementById('loginModeHint');

    function refreshIcons() {
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }

    function showError(message) {
        errorBox.textContent = message;
        errorBox.classList.remove('hidden');
    }

    function clearError() {
        errorBox.textContent = '';
        errorBox.classList.add('hidden');
    }

    function safeNextPath() {
        const next = new URLSearchParams(window.location.search).get('next');
        if (!next || !next.startsWith('/') || next.startsWith('//')) {
            return '/';
        }
        return next;
    }

    async function loadDemoCredentials() {
        try {
            const response = await fetch('/api/auth/demo-credentials', {
                method: 'POST',
                credentials: 'same-origin'
            });
            const payload = await response.json().catch(() => null);
            const data = response.ok && payload && payload.code === 200 ? payload.data : null;
            if (data && data.enabled) {
                usernameInput.value = data.username;
                passwordInput.value = data.password;
                // 程序赋值不会触发 input 事件，这里手动通知密码可见性按钮刷新。
                passwordInput.dispatchEvent(new Event('input', {bubbles: true}));
                loginModeHint.textContent = '目前为测试账号，可能存在多人同时使用！';
            } else {
                loginModeHint.textContent = '还没有账号？请联系管理员获取账号';
            }
        } catch (error) {
            // 未开启测试账号时保持手动登录，不向用户展示系统内部错误。
        }
    }

    form.addEventListener('submit', async event => {
        event.preventDefault();
        clearError();
        button.disabled = true;

        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({
                    username: usernameInput.value.trim(),
                    password: passwordInput.value
                })
            });
            const payload = await response.json().catch(() => null);

            if (!response.ok || !payload || payload.code !== 200) {
                showError((payload && payload.message) || '登录失败，请检查账号和密码');
                return;
            }

            if (payload.data && payload.data.mustChangePassword) {
                window.location.replace('/account.html?change-password=1');
                return;
            }
            window.location.replace(safeNextPath());
        } catch (error) {
            showError('无法连接服务，请稍后重试');
        } finally {
            button.disabled = false;
        }
    });

    loadDemoCredentials();
    refreshIcons();
})();
