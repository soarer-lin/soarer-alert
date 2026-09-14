(function () {
    'use strict';

    const forcedNotice = document.getElementById('forcedNotice');
    const errorBox = document.getElementById('passwordError');
    const successBox = document.getElementById('passwordSuccess');
    const form = document.getElementById('passwordForm');
    const button = document.getElementById('passwordButton');

    if (window.lucide && typeof window.lucide.createIcons === 'function') {
        window.lucide.createIcons();
    }

    function showMessage(element, message) {
        element.textContent = message;
        element.classList.remove('hidden');
    }

    function hideMessages() {
        [forcedNotice, errorBox, successBox].forEach(element => element.classList.add('hidden'));
    }

    async function initialize() {
        const user = await window.SuperBizAuth.getCurrentUser();
        if (new URLSearchParams(window.location.search).has('change-password') || user.mustChangePassword) {
            forcedNotice.classList.remove('hidden');
        }
    }

    initialize().catch(() => {
        showMessage(errorBox, '无法获取当前账号信息');
    });

    form.addEventListener('submit', async event => {
        event.preventDefault();
        hideMessages();

        const oldPassword = document.getElementById('oldPassword').value;
        const newPassword = document.getElementById('newPassword').value;
        const confirmPassword = document.getElementById('confirmPassword').value;

        if (newPassword !== confirmPassword) {
            showMessage(errorBox, '两次输入的新密码不一致');
            return;
        }
        if (newPassword.length < 6) {
            showMessage(errorBox, '新密码至少需要6位');
            return;
        }

        button.disabled = true;
        try {
            const response = await fetch('/api/auth/change-password', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({oldPassword, newPassword})
            });
            const payload = await response.json().catch(() => null);
            if (!response.ok || !payload || payload.code !== 200) {
                showMessage(errorBox, (payload && payload.message) || '密码更新失败');
                return;
            }
            showMessage(successBox, '密码已更新');
            form.reset();
        } catch (error) {
            showMessage(errorBox, '无法连接服务，请稍后重试');
        } finally {
            button.disabled = false;
        }
    });
})();
