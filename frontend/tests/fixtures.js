export const instances = [
  {id:1,name:'供应商联调 · Sandbox',enabled:true,authorizedOpenId:'ou_testuser0001',feishuAppId:'cli_sandbox',claudeWorkingDir:'/workspace/supplier-v2',shellWorkingDir:'/workspace/supplier-v2',claudeModel:'',sandboxLevel:2},
  {id:2,name:'接口分析 · Research',enabled:true,authorizedOpenId:'ou_testuser0001',feishuAppId:'cli_research',claudeWorkingDir:'/workspace/research',shellWorkingDir:'/workspace/research',claudeModel:'',sandboxLevel:1},
]
export const employee={id:7,name:'供应商工程师',emoji:'👩‍💻',roleDesc:'接口对接、业务验证与持续交付',instanceIds:[1],instances:[instances[0]],permissions:['supplier','e2e'],workSpec:'以真实接口结果为依据。\n所有变更需通过测试与人工审批。',autoPr:true,stats:{running:1,done:12},unreadCount:0,reportIntervalMin:60}
export const template={id:1,name:'API 供应商对接',mode:'API',builtin:false,description:'文档分析、接口实测与交付',nodesJson:JSON.stringify([{id:'n1',type:'agent',title:'接口文档分析',description:'整理接口映射和业务约束',prompt:'阅读 {docs}',contextStage:'analysis'},{id:'n2',type:'agent',title:'Postman 接口实测',description:'通过真实请求验证行为',contextStage:'probe'}])}
export const job={id:41,name:'星途供应商接入',supplierName:'星途供应商',supplierCode:'acme',kbCode:'acme',mode:'API',knowledgeMode:'WIKI',libraryPath:'/workspace/supplier-v2',instanceId:1,templateId:1,docsJson:'[]',skillsJson:'[]',status:'ACTIVE',currentNodeId:'n1',createdAt:'2026-11-04T09:00:00',nodes:[{nodeId:'n1',title:'接口文档分析',type:'agent',description:'梳理完整接口文档，确认取消与退款规则。',status:'PENDING',artifact:'',pendingQuestion:'',contextStage:'analysis'},{nodeId:'n2',title:'Postman 接口实测',type:'agent',description:'通过真实请求验证行为',status:'PENDING',artifact:'',pendingQuestion:'',contextStage:'probe'}]}
export const entries=[{id:201,kbCode:'acme',title:'供应商接口文档',type:'url',source:'https://example.com/docs/api',status:'DONE',contextStage:'all',metaJson:'{}'},{id:202,kbCode:'acme',title:'商务约定与对接记录',type:'chat',source:'',status:'DONE',contextStage:'all',metaJson:'{}'}]
export async function mockProduct(page){
  const writes=[]
  await page.route('**/api/**',async route=>{
    const req=route.request(),url=new URL(req.url()),p=url.pathname,m=req.method();let data={}
    if(m!=='GET') { writes.push({path:p,method:m,body:req.postData()});data=p==='/api/e2e/tasks'?{id:'new-task'}:p==='/api/supplier-jobs'?job:{ok:true};return route.fulfill({json:data}) }
    if(p==='/api/instances')data=instances
    else if(p==='/api/instances/status')data=instances.map(i=>({instanceId:i.id,code:'IDLE',emoji:'●',label:'空闲',detail:'等待下一次供应商联调任务',busy:false}))
    else if(p==='/api/employees/messages/unread')data={total:0}
    else if(p==='/api/employees')data=[employee]
    else if(p==='/api/employees/7')data={employee,demands:[],messages:[],unreadCount:0}
    else if(p==='/api/workflows')data=[template]
    else if(p==='/api/supplier-jobs')data=[job]
    else if(p==='/api/supplier-jobs/41')data=job
    else if(p.endsWith('/workflow'))data={status:'NOT_STARTED'}
    else if(p.endsWith('/knowledge-runs'))data=[{id:1,nodeId:'n1',stage:'analysis',mode:'WIKI',wikiRevision:3,knowledgeChars:3200,promptChars:4600,inputTokens:1950,outputTokens:640,cacheReadTokens:0,cacheWriteTokens:0,status:'COMPLETED',sourceDigest:'abcdef01234567890123456789'}]
    else if(p.endsWith('/wiki/schema'))data={contract:'保留来源证据，不得静默覆盖冲突。',instructions:'按接口、商务约定分别组织。'}
    else if(p.endsWith('/wiki'))data={status:'READY',revision:3,sourceCount:2,completedSources:2,rawChars:16000,inputTokens:5200,outputTokens:1800,pages:[{id:'overview',title:'供应商接入总览',kind:'overview',summary:'接口范围与关键约束',stages:['all'],links:['constraints']},{id:'constraints',title:'取消与退款约束',kind:'constraints',summary:'每次执行均需遵循',stages:['all'],links:['overview']}],critical:['constraints'],entrypoints:{all:['overview'],analysis:['overview'],probe:['constraints'],codegen:['constraints']}}
    else if(p.includes('/wiki/revisions/'))data={id:p.split('/').pop(),content:'# 供应商接入总览\n\n订单取消必须携带供应商订单号。\n\n[取消与退款约束](constraints.md)\n\n[完整原文](../raw/e201.md)'}
    else if(p==='/api/supplier-kb/acme/entries')data=entries
    else if(p.match(/\/entries\/\d+\/md$/))data={content:'# 接口文档\n\n取消必须传供应商订单号。'}
    else if(p.match(/\/entries\/\d+\/pages$/))data=[{id:301,title:'取消接口',url:'https://example.com/docs/cancel',status:'loaded',mdPath:'/tmp/demo.md'}]
    else if(p==='/api/hooks')data=[{id:5,name:'供应商对接经验沉淀',intent:'记录字段约定与接口验证经验',skillDir:'/workspace/.claude/skills/supplier',instanceIds:'1',maintainerInstanceId:2,triggerType:'AFTER_CHAT',enabled:true}]
    else if(p==='/api/hooks/changes')data=[]
    else if(p==='/api/e2e/tasks')data=[]
    else if(/\/(messages|queue|turns|runs|chat|devchat)$/.test(p))data=[]
    return route.fulfill({json:data})
  });return writes
}
