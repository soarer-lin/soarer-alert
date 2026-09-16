(function () {
    'use strict';

    /**
     * 初始化密码输入框的显示/隐藏按钮。
     * 浏览器自带按钮只在聚焦时出现，且无法控制显示时机；
     * 这里统一改为：输入框有内容时显示按钮，内容清空后隐藏按钮。
     */
    function bindPasswordInput(input) {
        let wrapper = input.closest('.auth-password');

        // 兼容未在 HTML 中手写 auth-password 包裹结构的密码框。
        if (!wrapper) {
            wrapper = document.createElement('div');
            wrapper.className = 'auth-password';
            input.replaceWith(wrapper);
            wrapper.appendChild(input);
        }

        let toggle = wrapper.querySelector('[data-password-toggle]');
        if (!toggle) {
            toggle = document.createElement('button');
            toggle.type = 'button';
            toggle.className = 'auth-icon-button';
            toggle.dataset.passwordToggle = `#${input.id || ''}`;
            toggle.setAttribute('aria-label', '显示密码');
            toggle.setAttribute('aria-pressed', 'false');
            toggle.innerHTML = `
                <span class="auth-icon" data-icon-show><i data-lucide="eye"></i></span>
                <span class="auth-icon hidden" data-icon-hide><i data-lucide="eye-off"></i></span>
            `;
            wrapper.appendChild(toggle);
        }

        const showIcon = toggle.querySelector('[data-icon-show]');
        const hideIcon = toggle.querySelector('[data-icon-hide]');
        if (!showIcon || !hideIcon) {
            return;
        }

        function refreshToggle() {
            const hasPassword = input.value.length > 0;
            toggle.classList.toggle('hidden', !hasPassword);
            toggle.disabled = !hasPassword;

            // 密码清空后恢复为隐藏状态，避免下一次输入时按钮图标状态错乱。
            if (!hasPassword && input.type === 'text') {
                input.type = 'password';
                toggle.setAttribute('aria-pressed', 'false');
                toggle.setAttribute('aria-label', '显示密码');
                showIcon.classList.remove('hidden');
                hideIcon.classList.add('hidden');
            }
        }

        if (!toggle.dataset.initialized) {
            toggle.dataset.initialized = 'true';
            toggle.addEventListener('click', () => {
                const visible = input.type === 'text';
                input.type = visible ? 'password' : 'text';
                toggle.setAttribute('aria-pressed', String(!visible));
                toggle.setAttribute('aria-label', visible ? '显示密码' : '隐藏密码');
                showIcon.classList.toggle('hidden', !visible);
                hideIcon.classList.toggle('hidden', visible);
                input.focus();
            });
        }

        // input 事件覆盖手动输入、粘贴、浏览器自动填充和清除操作。
        input.addEventListener('input', refreshToggle);
        input.addEventListener('change', refreshToggle);
        input.addEventListener('focus', refreshToggle);
        input.addEventListener('blur', refreshToggle);

        // 表单 reset 后值会被清空，但不会触发 input 事件，这里手动刷新。
        if (input.form) {
            input.form.addEventListener('reset', () => window.setTimeout(refreshToggle, 0));
        }

        refreshToggle();
    }

    function initialize() {
        document.querySelectorAll('input[type="password"]').forEach(bindPasswordInput);
        if (window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }
})();
