import { test, expect, firefox } from '@playwright/test'
import { mockProduct } from './fixtures'
test.beforeEach(async ({ page }) => { await mockProduct(page); await page.goto('/') })
async function imageScene(page, dark = false) {
  await expect(page.locator('.inst-card').first()).toBeVisible()
  await page.evaluate(dark => {
    const card = document.querySelector('.inst-card'), host = card.parentElement
    const canvas = document.createElement('canvas'); canvas.width=1000; canvas.height=600
    const ctx=canvas.getContext('2d');ctx.fillStyle=dark?'#080b12':'#f4f7ff';ctx.fillRect(0,0,1000,600)
    if (!dark) {ctx.fillStyle='#b4c8ff';ctx.fillRect(200,0,200,600);ctx.fillStyle='#f4bacb';ctx.fillRect(600,0,200,600)}
    host.style.backgroundImage=`url(${canvas.toDataURL()})`;host.style.backgroundSize='100% 100%'
    card.dataset.glass='';card.style.width='420px';card.style.height='270px';card.style.animation='none'
  }, dark)
  return page.locator('.inst-card').first()
}
test('flat cards keep solid surfaces; animated overlays get CSS glass without a lens',async({page})=>{
 const card=page.locator('.inst-card').first();await expect(card).not.toHaveAttribute('data-glass-active');const before=await card.boundingBox()
 await card.getByRole('button',{name:/编辑/}).click();const box=page.locator('dialog[open] > .modal-box');await expect(box).toHaveAttribute('data-glass-active','dark')
 const style=await box.evaluate(el=>({filter:getComputedStyle(el).backdropFilter,lens:el.style.getPropertyValue('--glass-lens')}))
 expect(style.filter).toContain('blur(2px)');expect(style.lens).toBe('');expect(await card.boundingBox()).toEqual(before)
 await page.screenshot({path:'../target/ui-screenshots/glass-drawer.png',animations:'disabled'})
})
test('settings persist independently per theme, zero tint and focus restoration work',async({page})=>{
 const trigger=page.getByRole('button',{name:'外观',exact:true});await trigger.click();await page.getByLabel('主题', {exact:true}).selectOption('dark')
 await expect(page.locator('html')).toHaveAttribute('data-theme','dark');await page.getByLabel(/深色玻璃浓度/).fill('0')
 const box=page.locator('dialog[open] > .modal-box');await expect.poll(()=>box.evaluate(el=>el.style.getPropertyValue('--glass-alpha'))).toBe('0')
 await expect.poll(()=>box.evaluate(el=>el.style.getPropertyValue('--glass-brightness'))).toBe('1')
 await page.getByLabel(/浅色玻璃浓度/).fill('1.25');await page.keyboard.press('Escape');await expect(trigger).toBeFocused();await page.reload();await trigger.click()
 await expect(page.getByLabel('主题',{exact:true})).toHaveValue('dark');await expect(page.getByLabel(/深色玻璃浓度/)).toHaveValue('0');await expect(page.getByLabel(/浅色玻璃浓度/)).toHaveValue('1.25')
 await page.getByLabel('主题',{exact:true}).selectOption('light');await expect(page.locator('html')).toHaveAttribute('data-theme','light')
})
test('readable image cards get decoded unique lens maps; resizing reuses patches',async({page})=>{
 const card=await imageScene(page);await expect(card).toHaveAttribute('data-glass-source','image');await expect.poll(()=>card.evaluate(el=>el.style.getPropertyValue('--glass-lens'))).toMatch(/url\(#glass-rim-/)
 const before=await page.locator('filter[id^="glass-rim-"] feImage').evaluateAll(els=>els.map(el=>el.getAttribute('href')));expect(before).toHaveLength(8)
 await page.screenshot({path:'../target/ui-screenshots/glass-image-lens.png',animations:'disabled'})
 await card.evaluate(el=>el.style.width='500px');await expect.poll(()=>page.locator('filter[id^="glass-rim-"]').getAttribute('width')).toBe('500')
 expect(await page.locator('filter[id^="glass-rim-"] feImage').evaluateAll(els=>els.map(el=>el.getAttribute('href')))).toEqual(before)
 await card.evaluate(el=>{const copy=el.cloneNode(true);copy.style.removeProperty('--glass-lens');delete copy.dataset.glassActive;el.parentElement.append(copy)})
 await expect(page.locator('filter[id^="glass-rim-"]')).toHaveCount(2)
 expect(new Set(await page.locator('filter[id^="glass-rim-"]').evaluateAll(els=>els.map(el=>el.id))).size).toBe(2)
})
test('sampling responds to background changes and compositing disables only the lens',async({page})=>{
 const card=await imageScene(page,true);await expect(card).toHaveAttribute('data-glass-active','light')
 await card.evaluate(el=>el.style.willChange='transform');await expect.poll(()=>card.evaluate(el=>el.style.getPropertyValue('--glass-lens'))).toBe('');await expect(card).toHaveAttribute('data-glass-source','image')
 await card.evaluate(el=>{const canvas=document.createElement('canvas');canvas.width=1000;canvas.height=600;const ctx=canvas.getContext('2d');ctx.fillStyle='white';ctx.fillRect(0,0,1000,600);el.parentElement.style.backgroundImage=`url(${canvas.toDataURL()})`})
 await expect(card).toHaveAttribute('data-glass-active','dark')
})
test('unreadable scenes and forced colors keep accessible solid fallback without refraction',async({page})=>{
 const card=await imageScene(page);await expect(card).toHaveAttribute('data-glass-source','image')
 await card.evaluate(el=>{el.parentElement.style.backgroundImage='url(data:image/png;base64,not-an-image)'})
 await expect(card).toHaveAttribute('data-glass-source','unreadable');await expect.poll(()=>card.evaluate(el=>el.style.getPropertyValue('--glass-lens'))).toBe('')
 await page.emulateMedia({forcedColors:'active'});await expect.poll(()=>card.evaluate(el=>getComputedStyle(el).backdropFilter)).toBe('none')
 expect(await card.evaluate(el=>getComputedStyle(el).backgroundImage)).toBe('none')
})
test('dark mode remains readable on mobile and preserves semantic red actions',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'外观',exact:true}).click();await page.getByLabel('主题',{exact:true}).selectOption('dark');await page.keyboard.press('Escape')
 await page.locator('.inst-card').nth(1).getByRole('button',{name:/删除/}).click();const pop=page.getByRole('alertdialog');await expect(pop).toHaveAttribute('data-glass-active','light')
 const red=await pop.locator('.btn-danger').evaluate(el=>({color:getComputedStyle(el).color,background:getComputedStyle(el).backgroundColor}));expect(red.color).toBe('rgb(255, 255, 255)');expect(red.background).toBe('rgb(205, 70, 84)')
 expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);await page.screenshot({path:'../target/ui-screenshots/glass-dark-mobile.png',fullPage:true,animations:'disabled'})
})


test('reduced transparency removes glass and rows share one ink decision',async({page})=>{
 const card=await imageScene(page,true);await expect(card).toHaveAttribute('data-glass-active','light')
 await card.evaluate(el=>{const next=el.parentElement.querySelectorAll('.inst-card')[1];next.dataset.glass='';next.style.height='270px';next.style.animation='none'})
 const neighbor=page.locator('.inst-card').nth(1);await expect(neighbor).toHaveAttribute('data-glass-active','light')
 const session=await page.context().newCDPSession(page)
 await session.send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-transparency',value:'reduce'}]})
 await expect.poll(()=>card.evaluate(el=>getComputedStyle(el).backdropFilter)).toBe('none')
 await expect(page.locator('filter[id^="glass-rim-"]')).toHaveCount(0)
 await page.getByRole('button',{name:'外观',exact:true}).click();const box=page.locator('dialog[open] > .modal-box');await expect(box).toHaveAttribute('data-glass-active','dark');await box.hover()
 expect(await box.evaluate(el=>getComputedStyle(el).backgroundColor)).toBe('rgb(255, 255, 255)');expect(await box.evaluate(el=>getComputedStyle(el).backdropFilter)).toBe('none')
 await page.keyboard.press('Escape')
 await session.send('Emulation.setEmulatedMedia',{features:[]})
 await expect(card).toHaveAttribute('data-glass-source','image')
})


test('superellipse geometry changes patches while unsupported corners keep CSS glass',async({page})=>{
 const card=await imageScene(page);await expect.poll(()=>page.locator('filter[id^="glass-rim-"]').count()).toBe(1)
 const round=await page.locator('filter[id^="glass-rim-"] feImage').first().getAttribute('href')
 await card.evaluate(el=>el.style.cornerShape='superellipse(2)')
 await expect.poll(()=>page.locator('filter[id^="glass-rim-"] feImage').first().getAttribute('href')).not.toBe(round)
 await card.evaluate(el=>el.style.borderTopLeftRadius='50%')
 await expect.poll(()=>card.evaluate(el=>el.style.getPropertyValue('--glass-lens'))).toBe('')
 expect(await card.evaluate(el=>getComputedStyle(el).backdropFilter)).toContain('blur(2px)')
})

test('Firefox keeps the CSS material without attaching URL filters',async({baseURL})=>{
 const browser=await firefox.launch()
 try {
   const context=await browser.newContext({baseURL}),page=await context.newPage();await mockProduct(page);await page.goto('/')
   const card=await imageScene(page);await expect(card).toHaveAttribute('data-glass-source','image')
   expect(await card.evaluate(el=>getComputedStyle(el).backdropFilter)).toContain('blur(2px)')
   expect(await card.evaluate(el=>el.style.getPropertyValue('--glass-lens'))).toBe('')
   await expect(page.locator('filter[id^="glass-rim-"]')).toHaveCount(0)
 } finally { await browser.close() }
})
