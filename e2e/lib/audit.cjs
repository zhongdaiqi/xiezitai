// 审核助手：写台从「注册需审核」之后，自助注册的用户默认是 PENDING —— 登录会被 403 挡下。
// 端到端脚本要验证的是「审核通过之后才可用」，所以注册完必须先放行。
// 这里是真实调后台审核接口（而不是直接改库），顺带把审核接口本身也跑通了。
async function auditUser(base, adminToken, username, status = 'APPROVED', note) {
  const r = await fetch(base + '/api/admin/users', { headers: { Authorization: 'Bearer ' + adminToken } });
  if (!r.ok) throw new Error('取用户列表失败: HTTP ' + r.status);
  const list = await r.json();
  const u = (list || []).find(x => x.username === username);
  if (!u) throw new Error('后台用户列表里找不到 ' + username);
  const a = await fetch(base + '/api/admin/users/' + u.id + '/audit', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + adminToken },
    body: JSON.stringify({ status, note })
  });
  if (!a.ok) throw new Error('审核 ' + username + ' 失败: HTTP ' + a.status);
  return u.id;
}

module.exports = { auditUser };
