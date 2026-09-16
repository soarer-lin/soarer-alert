(function () {
    'use strict';

    const originalFetch = window.fetch.bind(window);

    function requestUrl(input) {
        if (input instanceof Request) {
            return new URL(input.url, window.location.href);
        }
        try {
            return new URL(String(input), window.location.href);
        } catch (error) {
            return null;
        }
    }

    function isApiRequest(url) {
        return url !== null
            && url.origin === window.location.origin
            && url.pathname.startsWith('/api/');
    }

    function safeNextPath() {
        const next = new URLSearchParams(window.location.search).get('next');
        if (!next || !next.startsWith('/') || next.startsWith('//')) {
            return '/';
        }
        return next;
    }

    function redirectToLogin() {
        if (window.location.pathname === '/login.html') {
            return;
        }
        const next = encodeURIComponent(window.location.pathname + window.location.search);
        window.location.replace(`/login.html?next=${next}`);
    }

    async function fetchWithSession(input, init) {
        const url = requestUrl(input);
        if (!isApiRequest(url)) {
            return originalFetch(input, init);
        }

        const options = init === undefined ? {} : init;
        const response = await originalFetch(input, {...options, credentials: 'same-origin'});
        if (response.status === 401 && url.pathname !== '/api/auth/login') {
            redirectToLogin();
        }
        return response;
    }

    function websocketUrl(path) {
        const url = new URL(path, window.location.origin);
        // WebSocket 必须使用 ws/wss 协议，不能直接复用 http/https。
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
        return url.toString();
    }

    async function getCurrentUser() {
        const response = await originalFetch('/api/auth/me', {credentials: 'same-origin'});
        if (!response.ok) {
            throw new Error('Unable to load current user');
        }
        const payload = await response.json();
        if (!payload || payload.code !== 200) {
            throw new Error((payload && payload.message) || 'Unable to load current user');
        }
        return payload.data;
    }

    function bindAuthChrome() {
        const nameTargets = document.querySelectorAll('[data-auth-name]');
        const roleTargets = document.querySelectorAll('[data-auth-role]');
        const accountTargets = document.querySelectorAll('[data-auth-account]');
        const adminTargets = document.querySelectorAll('[data-auth-admin]');
        const opsTargets = document.querySelectorAll('[data-auth-ops]');
        const logoutTargets = document.querySelectorAll('[data-auth-logout]');

        getCurrentUser().then(user => {
            const authenticated = Boolean(user && user.authenticated);
            const displayName = authenticated ? (user.displayName || user.username) : '未登录';
            const roleLabels = {ADMIN: '管理员', OPS: '运维'};
            const roleName = authenticated ? (roleLabels[user.role] || user.role) : '';
            const canUseOps = authenticated && (user.role === 'ADMIN' || user.role === 'OPS');

            nameTargets.forEach(target => {
                target.textContent = displayName;
            });
            roleTargets.forEach(target => {
                target.textContent = roleName;
            });
            accountTargets.forEach(target => {
                target.classList.toggle('hidden', !authenticated);
            });
            logoutTargets.forEach(target => {
                target.classList.toggle('hidden', !authenticated);
            });
            adminTargets.forEach(target => {
                target.classList.toggle('hidden', !authenticated || user.role !== 'ADMIN');
            });
            opsTargets.forEach(target => {
                target.classList.toggle('hidden', !canUseOps);
            });
        }).catch(() => {
            nameTargets.forEach(target => {
                target.textContent = '未登录';
            });
            roleTargets.forEach(target => {
                target.textContent = '';
            });
            accountTargets.forEach(target => target.classList.add('hidden'));
            logoutTargets.forEach(target => target.classList.add('hidden'));
            adminTargets.forEach(target => target.classList.add('hidden'));
            opsTargets.forEach(target => target.classList.add('hidden'));
        });

        document.addEventListener('click', async event => {
            const target = event.target.closest('[data-auth-logout]');
            if (!target) {
                return;
            }
            event.preventDefault();
            target.disabled = true;
            try {
                await originalFetch('/api/auth/logout', {
                    method: 'POST',
                    credentials: 'same-origin'
                });
            } finally {
                window.location.href = '/login.html';
            }
        });
    }

    window.fetch = fetchWithSession;
    window.SuperBizAuth = {
        getCurrentUser,
        safeNextPath,
        websocketUrl
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bindAuthChrome);
    } else {
        bindAuthChrome();
    }
})();
