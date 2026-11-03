import { test, expect } from '@playwright/test'
import { mockProduct } from './fixtures'
const drawer = page => page.locator('dialog.surface-drawer[open]')
const confirmation = page => page.locator('dialog.surface-dialog[open]')

test.beforeEach(async ({ page }) => { await mockProduct(page) })
test('workspace navigation and desktop surface render without runtime errors',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/supplier?job=41');await expect(page.getByText('星途供应商接入').first()).toBeVisible();await expect(page.locator('.workspace-nav .nav-item.active')).toContainText('供应商工作台');await page.screenshot({path:'../target/ui-screenshots/supplier-desktop.png',fullPage:true,animations:'disabled'});expect(errors).toEqual([])
})
test('instance drawer traps focus, previews drafts, protects close and restores its trigger',async({page})=>{
 await page.goto('/');const card=page.locator('.inst-card').first();const trigger=card.getByRole('button',{name:/编辑/});await trigger.click();await expect(drawer(page)).toBeVisible();const name=drawer(page).locator('input').first();await name.fill('未保存的实例名称');await expect(card).toContainText('未保存的实例名称');await page.screenshot({path:'../target/ui-screenshots/instance-drawer.png',animations:'disabled'});await page.keyboard.press('Escape');await expect(confirmation(page)).toContainText('放弃未保存的更改');await confirmation(page).getByRole('button',{name:'继续编辑'}).click();await expect(confirmation(page)).toHaveCount(0);await expect(name).toBeFocused();await expect(name).toHaveValue('未保存的实例名称');await page.keyboard.press('Escape');await confirmation(page).getByRole('button',{name:'放弃更改'}).click();await expect(drawer(page)).toHaveCount(0);await expect(trigger).toBeFocused();await expect(card).not.toContainText('未保存的实例名称')
})
test('idle unbound instance uses an anchored popconfirm; Escape and outside click cancel',async({page})=>{
 await page.goto('/');const trigger=page.locator('.inst-card').nth(1).getByRole('button',{name:/删除/});await trigger.click();const pop=page.getByRole('alertdialog');await expect(pop).toBeVisible();const a=await trigger.boundingBox(),b=await pop.boundingBox();expect(Math.abs(b.y-a.y)).toBeLessThan(350);await page.keyboard.press('Escape');await expect(pop).toHaveCount(0);await expect(trigger).toBeFocused();await trigger.click();await page.locator('.workspace-header').click();await expect(pop).toHaveCount(0)
})
test('employee deletion uses a named dialog and cancellation makes no request',async({page})=>{
 const writes=await mockProduct(page);await page.goto('/employees');await page.getByRole('button',{name:/删除/}).click();await expect(confirmation(page)).toContainText('全部需求记录');await expect(confirmation(page).getByRole('button',{name:'删除员工'})).toBeVisible();await page.keyboard.press('Escape');expect(writes).toEqual([])
})
test('tooltip has a first delay, works on focus, has aria-describedby and remains in viewport',async({page})=>{
 await page.goto('/');const target=page.locator('.status-badge[data-tooltip]').first();await expect(target).toBeVisible();await target.hover();await expect(page.getByRole('tooltip')).toHaveCount(0);await expect(page.getByRole('tooltip')).toBeVisible();const id=await page.getByRole('tooltip').getAttribute('id');await expect(target).toHaveAttribute('aria-describedby',id);const bounds=await page.getByRole('tooltip').boundingBox();expect(bounds.x).toBeGreaterThanOrEqual(0);expect(bounds.x+bounds.width).toBeLessThanOrEqual(1280);await page.keyboard.press('Escape');await expect(page.getByRole('tooltip')).toHaveCount(0);await target.focus();await expect(page.getByRole('tooltip')).toBeVisible()
})
test('E2E creation is a reloadable page and dirty navigation can be cancelled',async({page})=>{
 await page.goto('/e2e?create=1');await expect(page.locator('.page-surface')).toContainText('新建 E2E');await expect(page.locator('dialog')).toHaveCount(0);await page.locator('.page-surface textarea').fill('验证订单取消');await page.getByRole('link',{name:'Skill 维护',exact:true}).click();await expect(confirmation(page)).toContainText('未保存的更改');await confirmation(page).getByRole('button',{name:'继续编辑'}).click();await expect(page).toHaveURL(/create=1/);await expect(page.locator('.page-surface textarea')).toHaveValue('验证订单取消');await page.locator('.page-surface .surface-close').click();await confirmation(page).getByRole('button',{name:'放弃更改'}).click();await expect(page.locator('.page-surface')).toHaveCount(0)
})
test('Wiki reader restores a fixed revision from URL and navigates source links',async({page})=>{
 await page.goto('/supplier?job=41&wiki=overview&revision=3');await expect(page.locator('.page-surface')).toContainText('版本 #3');await expect(page.locator('.page-surface')).toContainText('订单取消必须');await page.locator('.page-surface').getByRole('link',{name:'完整原文'}).click();await expect(page).toHaveURL(/raw=1/);await expect(page).toHaveURL(/revision=3/);await page.reload();await expect(page.locator('.page-surface')).toContainText('原始来源');await page.locator('.page-surface .surface-close').click();await expect(page).toHaveURL(/job=41$/)
})
test('employee SPEC is a click reader instead of hover-only document',async({page})=>{
 await page.goto('/employees');await page.locator('[data-document]').click();await expect(drawer(page)).toContainText('所有变更需通过测试');await page.keyboard.press('Escape');await expect(drawer(page)).toHaveCount(0)
})
test('supplier creation and template drafts prompt before navigating away',async({page})=>{
 await page.goto('/supplier?view=workflows');await page.getByRole('button',{name:/编辑/}).first().click();const input=page.locator('.draft-page input').first();await input.fill('修改中的模板');await page.getByRole('button',{name:'← 返回',exact:true}).click();await expect(confirmation(page)).toBeVisible();await confirmation(page).getByRole('button',{name:'继续编辑'}).click();await expect(input).toHaveValue('修改中的模板')
})
test('mobile navigation, tap hints and drawers fit the viewport',async({browser})=>{
 const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});const page=await context.newPage();await mockProduct(page);await page.goto('/');const hint=page.locator('[data-tooltip]').first();await hint.tap();await expect(page.getByRole('tooltip')).toBeVisible();await page.locator('.inst-card').first().getByRole('button',{name:/编辑/}).tap();await expect(drawer(page)).toBeVisible();expect((await drawer(page).boundingBox()).width).toBeLessThanOrEqual(390);await page.screenshot({path:'../target/ui-screenshots/instance-mobile.png',fullPage:true,animations:'disabled'});await page.locator('dialog .surface-close').tap();expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);await context.close()
})

test('nested SPEC completion keeps the parent employee draft protected',async({page})=>{
 await page.goto('/employees?employee=7');await page.getByRole('button',{name:/编辑/}).first().click();const parent=drawer(page).first();await parent.getByRole('button',{name:/编辑 SPEC/}).click();const child=drawer(page).last();await child.locator('textarea').fill('只修改 SPEC 也必须保留草稿保护。');await child.getByRole('button',{name:'完成',exact:true}).click();await expect(drawer(page)).toHaveCount(1);await parent.getByRole('button',{name:'取消',exact:true}).click();await expect(confirmation(page)).toContainText('未保存的更改');await confirmation(page).getByRole('button',{name:'继续编辑'}).click();await expect(parent.locator('.spec-preview')).toContainText('只修改 SPEC')
})
test('discarding a nested node editor leaves earlier template changes protected',async({page})=>{
 await page.goto('/supplier?view=workflows');await page.getByRole('button',{name:/编辑/}).first().click();await page.locator('.draft-page input').first().fill('保留的模板修改');await page.locator('.wf-editor-row').first().getByRole('button',{name:'编辑',exact:true}).click();await drawer(page).locator('input').first().fill('应放弃的节点修改');await page.keyboard.press('Escape');await confirmation(page).getByRole('button',{name:'放弃更改'}).click();await expect(drawer(page)).toHaveCount(0);await page.getByRole('button',{name:'← 返回',exact:true}).click();await expect(confirmation(page)).toContainText('未保存的更改')
})
test('Markdown cancel actually restores the original text',async({page})=>{
 await page.goto('/supplier?job=41');await page.getByRole('button',{name:/产物.*校正/}).first().click();await drawer(page).getByRole('button',{name:/编辑校正/}).click();await drawer(page).locator('textarea').fill('不应保留的修改');await drawer(page).getByRole('button',{name:'取消编辑',exact:true}).click();await confirmation(page).getByRole('button',{name:'放弃更改'}).click();await expect(drawer(page)).toContainText('取消必须传供应商订单号');await expect(drawer(page)).not.toContainText('不应保留的修改')
})

test('live tooltips refresh and disappear when their trigger is removed',async({page})=>{
 await page.goto('/');const target=page.locator('.status-badge[data-tooltip]').first();await target.hover();await expect(page.getByRole('tooltip')).toBeVisible();await target.evaluate(el=>el.setAttribute('data-tooltip','正在执行取消接口验证'));await expect(page.getByRole('tooltip')).toHaveText('正在执行取消接口验证');await target.evaluate(el=>el.remove());await expect(page.getByRole('tooltip')).toHaveCount(0)
})
test('supplier workspace remains readable without page overflow on mobile',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/supplier?job=41');await expect(page.locator('.supplier-workspace-grid')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);await page.screenshot({path:'../target/ui-screenshots/supplier-mobile.png',fullPage:true,animations:'disabled'})
})

test('unfinished workflow answers are protected and successful submission clears the draft',async({page})=>{
 await page.route('**/api/supplier-jobs/41/workflow',route=>route.fulfill({json:{status:'ACTIVE',run:{notifyFeishu:false},tasks:[{id:'task-1',name:'补充业务信息',kind:'input',nodeId:'n1',nodeTitle:'接口文档分析',description:'请说明取消规则',question:'供应商是否允许部分取消？',attachments:[]}],history:[],notices:[],attempts:[]}}));await page.goto('/supplier?job=41');const answer=page.getByRole('textbox',{name:'答复 补充业务信息'});await answer.fill('支持部分取消');await page.getByRole('link',{name:'数字员工',exact:true}).click();await expect(confirmation(page)).toContainText('未保存的更改');await confirmation(page).getByRole('button',{name:'继续编辑'}).click();await expect(answer).toHaveValue('支持部分取消');await page.getByRole('button',{name:'提交并继续',exact:true}).click();await expect(answer).toHaveValue('');await page.getByRole('link',{name:'数字员工',exact:true}).click();await expect(page).toHaveURL(/employees$/);await expect(confirmation(page)).toHaveCount(0)
})
