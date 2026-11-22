'use strict';
// 供应商配置结构断言（check-config-shape.js / fetch-config-center.js 共用）。
// 结构依据（11-18-adhoc-supplier-config PRD 样例）：顶层 SupplierID / IsEnableAntErrType / Urls / AccountDic；
// Urls 七键名固定；AccountDic 以 SupplierAccountID 字符串为 key，每账号 paymentType 为数字、
// ConfirmMode ∈ {INSTANT, ONREQUEST}。
// 纯确定性断言，无任何模型参与——返回问题清单（空 = 通过）。

const REQUIRED_URL_KEYS = [
  'hotelcodesUrl', 'hotelinfosUrl', 'hotelsearchUrl', 'precheckUrl',
  'createUrl', 'searchUrl', 'cancelUrl',
];
const CONFIRM_MODES = ['INSTANT', 'ONREQUEST'];

function validateConfigShape(cfg) {
  const problems = [];
  if (cfg === null || typeof cfg !== 'object' || Array.isArray(cfg)) {
    return ['顶层必须是 JSON 对象'];
  }
  if (typeof cfg.SupplierID !== 'number' || !Number.isFinite(cfg.SupplierID)) {
    problems.push('SupplierID 缺失或不是数字（当前: ' + JSON.stringify(cfg.SupplierID) + '）');
  }
  const urls = cfg.Urls;
  if (urls === null || typeof urls !== 'object' || Array.isArray(urls)) {
    problems.push('Urls 缺失或不是对象');
  } else {
    for (const k of REQUIRED_URL_KEYS) {
      const v = urls[k];
      if (typeof v !== 'string' || !v.trim()) {
        problems.push('Urls.' + k + ' 缺失或不是非空字符串');
      }
    }
  }
  const dic = cfg.AccountDic;
  if (dic === null || typeof dic !== 'object' || Array.isArray(dic)) {
    problems.push('AccountDic 缺失或不是字典对象');
  } else {
    const entries = Object.entries(dic);
    if (entries.length === 0) problems.push('AccountDic 为空（至少要有一个账号）');
    for (const [accKey, acc] of entries) {
      if (acc === null || typeof acc !== 'object' || Array.isArray(acc)) {
        problems.push('AccountDic.' + accKey + ' 不是对象');
        continue;
      }
      const mode = typeof acc.ConfirmMode === 'string' ? acc.ConfirmMode.trim().toUpperCase() : acc.ConfirmMode;
      if (!CONFIRM_MODES.includes(mode)) {
        problems.push('AccountDic.' + accKey + '.ConfirmMode = ' + JSON.stringify(acc.ConfirmMode)
          + '，必须是 ' + CONFIRM_MODES.join(' / ') + ' 之一');
      }
      if (typeof acc.paymentType !== 'number' || !Number.isFinite(acc.paymentType)) {
        problems.push('AccountDic.' + accKey + '.paymentType 缺失或不是数字（当前: '
          + JSON.stringify(acc.paymentType) + '）');
      }
    }
  }
  return problems;
}

module.exports = { validateConfigShape, REQUIRED_URL_KEYS, CONFIRM_MODES };
