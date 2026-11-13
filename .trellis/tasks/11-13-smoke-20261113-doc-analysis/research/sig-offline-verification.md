# 游由签名算法离线核验（非接口实测）

- 时间：2026-11-13；执行方式：本地 Python 重算（脚本 `C:\Users\yixi.ning\AppData\Local\Temp\yoyo-sig-check\verify_sig.py`），不调用任何供应商 HTTP 接口。
- 依据：Wiki v20 `constraints/yoyo-api-signature.md`、`api/yoyo-signature-example.md`、raw `e306.md`（《接口签名》原文）。

## 核验项与结果

| 核验项 | 本地计算值 | 文档期望值 | 结果 |
|---|---|---|---|
| bodyHash = SHA256_HEX(`{"regionId":"123","keyword":"tokyo"}`) | `2bd8da32f62983d184dbb9585a77a9e84bf31041991f5530e80d8faeb083e59e` | 同左（e306） | ✅ 一致 |
| 空 body 边界值 SHA256_HEX(b"") | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | 同左（e306） | ✅ 一致 |
| signature = BASE64(HMAC_SHA256(`sk_test_456`, 6 行 signingString)) | `5CDmXPEfEny5efYqpLCTphHC1npnhTcC38qFtji4K9E=` | 同左（e306） | ✅ 一致 |

signingString（repr）：
`'YOYO-HMAC-SHA256\nak_test_123\n1760000000000\nnonce_test_abc\npageSize=20&pageNum=1\n2bd8da32f62983d184dbb9585a77a9e84bf31041991f5530e80d8faeb083e59e'`

## 结论
文档描述的签名算法**内部自洽、可复现**：按 constraints 页规则实现即可逐字节复现官方算例。算法理解无歧义；剩余不确定性全部集中在未决项（时间戳单位/有效期、nonce 规则、错误码——见 question 页）与缺失项（环境地址、真实 ak/sk、业务接口定义）。
