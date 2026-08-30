// 生成跨端加密测试向量：用 Node crypto 按小程序相同参数产出备份文件。
// 小程序的 crypto-lite 已与 Node 原生 crypto 逐字节交叉验证（见 overview.md），
// 因此本脚本输出即等价于小程序端真实导出。
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.join(__dirname, '..', 'app', 'src', 'test', 'resources');
fs.mkdirSync(outDir, { recursive: true });

const KNOWN = 'PRIVATE_VAULT_VERIFY_TOKEN_v1';
const b64 = (buf) => buf.toString('base64');

function encField(plain, key) {
  const iv = crypto.randomBytes(16);
  const c = crypto.createCipheriv('aes-256-cbc', key, iv);
  const ct = Buffer.concat([c.update(Buffer.from(plain, 'utf8')), c.final()]);
  return { enc: b64(ct), iv: b64(iv) };
}

function makeMeta(master) {
  const salt = crypto.randomBytes(16);
  const key = crypto.pbkdf2Sync(master, salt, 100000, 32, 'sha256');
  const v = encField(KNOWN, key);
  return {
    saltB64: b64(salt),
    verifyB64: v.enc,
    verifyIvB64: v.iv,
    key,
  };
}

const cryptoBlock = { kdf: 'PBKDF2', hash: 'SHA-256', iterations: 100000, keyLen: 32, saltLen: 16, ivLen: 16, cipher: 'AES-256-CBC', encoding: 'base64' };

// ---------- fixture 1: custom 模式（通用交换格式） ----------
const master = 'test-master-123';
const m1 = makeMeta(master);
const items = [
  {
    _id: 'l_fix01abc',
    title: '银行账号',
    category: '金融',
    ...(() => { const a = encField('user-john-01', m1.key); const s = encField('P@ss中文✅-secret', m1.key); const n = encField('备注：含 emoji 🎉 与中文', m1.key); return { accountEnc: a.enc, accountIv: a.iv, secretEnc: s.enc, secretIv: s.iv, noteEnc: n.enc, noteIv: n.iv }; })(),
    createdAt: 1724000000000,
    updatedAt: 1724000100000,
  },
  {
    _id: 'l_fix02def',
    title: 'GitHub',
    category: '开发',
    ...(() => { const a = encField('dev@example.com', m1.key); const s = encField('gh_pat_1234567890', m1.key); const n = encField('token 备用', m1.key); return { accountEnc: a.enc, accountIv: a.iv, secretEnc: s.enc, secretIv: s.iv, noteEnc: n.enc, noteIv: n.iv }; })(),
    createdAt: 1724000200000,
    updatedAt: 1724000300000,
  },
];
const customBackup = {
  format: 'private-vault-backup',
  version: 1,
  exportedAt: 1724000400000,
  crypto: cryptoBlock,
  data: {
    meta: {
      saltB64: m1.saltB64, verifyB64: m1.verifyB64, verifyIvB64: m1.verifyIvB64,
      pwdMode: 'custom', initialized: true, createdAt: 1724000000000, updatedAt: 1724000000000,
    },
    items,
  },
};

// ---------- fixture 2: 小程序 default 模式（openid 派生密钥） ----------
const openid = 'oX-test-openid-0042';
const wxbMaster = 'wxb-def-v1::' + openid;
const m2 = makeMeta(wxbMaster);
const wxbItem = {
  _id: 'l_fix03ghi',
  title: '网易邮箱',
  category: '邮箱',
  ...(() => { const a = encField('user@163.com', m2.key); const s = encField('mail-pwd-🔧', m2.key); const n = encField('默认模式迁移测试', m2.key); return { accountEnc: a.enc, accountIv: a.iv, secretEnc: s.enc, secretIv: s.iv, noteEnc: n.enc, noteIv: n.iv }; })(),
  createdAt: 1724000500000,
  updatedAt: 1724000600000,
};
const wxbBackup = {
  format: 'private-vault-backup',
  version: 1,
  exportedAt: 1724000700000,
  crypto: { ...cryptoBlock, masterRef: 'wxb-def-v1' },
  data: {
    meta: {
      saltB64: m2.saltB64, verifyB64: m2.verifyB64, verifyIvB64: m2.verifyIvB64,
      pwdMode: 'default', initialized: true, createdAt: 1724000500000, updatedAt: 1724000500000,
      openid,
    },
    items: [wxbItem],
  },
};

fs.writeFileSync(path.join(outDir, 'backup_custom.json'), JSON.stringify(customBackup, null, 2));
fs.writeFileSync(path.join(outDir, 'backup_wxb_default.json'), JSON.stringify(wxbBackup, null, 2));

// ---------- 直接向量（用于引擎级断言） ----------
const pbkdf2Hex = crypto.pbkdf2Sync('password', 'salt', 1, 32, 'sha256').toString('hex');
const aesKey = crypto.pbkdf2Sync('password', 'salt', 1, 32, 'sha256');
const fixedVector = encField('Penly cross-platform vector 中文✅', aesKey);
console.log(JSON.stringify({
  pbkdf2Hex,
  fixedVectorKeyHex: aesKey.toString('hex'),
  fixedVector,
}, null, 2));
