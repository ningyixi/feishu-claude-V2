import { test } from 'node:test'
import assert from 'node:assert/strict'
import { chooseInk, contrast, luminance, material, summarize } from '../src/glass/material.js'

test('5x3 sample discards two extremes on each end', () => {
  const scene = summarize([[0,0,0],[1,1,1],...Array.from({length:11},()=>[120,100,80]),[254,254,254],[255,255,255]])
  assert.deepEqual(scene.rgb,[120,100,80]); assert.equal(scene.texture,1)
  assert.equal(scene.luminance,luminance(scene.rgb))
})
test('ink hysteresis and dark theme do not flicker at .5', () => {
  assert.equal(chooseInk('light',.54,'light'),'light')
  assert.equal(chooseInk('light',.46,'dark'),'dark')
  assert.equal(chooseInk('light',.56,'light'),'dark')
  assert.equal(chooseInk('light',.44,'dark'),'light')
  assert.equal(chooseInk('dark',1,'dark'),'light')
})
test('zero tint removes color adjustments while dense fallback stays finite', () => {
  for (const theme of ['light','dark']) {
    const zero = material(theme,theme==='dark'?'light':'dark',null,0)
    assert.equal(zero.alpha,0); assert.equal(zero.brightness,1); assert.equal(zero.saturation,1)
    assert.equal(zero.density,1)
    assert.ok(material(theme,'light',null,1).alpha <= 1)
  }
})
test('default fixed secondary ink passes AA on darkest and busiest supported scenes', () => {
  const scenes = [Array.from({length:15},()=>[0,0,0]),Array.from({length:15},()=>[255,255,255]),Array.from({length:15},(_,i)=>[[255,20,20],[0,0,0],[255,255,255],[0,100,255],[200,255,0]][i%5])]
  for (const points of scenes) for (const theme of ['light','dark']) {
    const scene = summarize(points), ink = chooseInk(theme,scene.luminance), m = material(theme,ink,scene)
    for (const point of points) {
      const gray = .2126*point[0]+.7152*point[1]+.0722*point[2]
      let bg = point.map((v,i)=>Math.max(0,Math.min(255,(gray+(v-gray)*m.saturation)*m.brightness))*(1-m.alpha)+m.tint[i]*m.alpha)
      const white = ink === 'light'
      if (white) bg = bg.map(v=>v*(theme==='dark'?.965:.94)+255*(theme==='dark'?.035:.06))
      const text = bg.map((v,i)=>white?v*.36+[235,235,245][i]*.64:v*.3+.7*[58,58,64][i])
      assert.ok(contrast(bg,text)>=4.5,`${theme}/${ink} alpha=${m.alpha}: ${contrast(bg,text)}`)
    }
  }
})
