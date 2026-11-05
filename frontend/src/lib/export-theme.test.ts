import { describe, it, expect, beforeEach } from 'vitest'
import { asDarkClone, withDarkTheme } from './export-theme'

const root = () => document.documentElement

beforeEach(() => {
  root().className = 'light'
})

describe('export theme', () => {
  it('reads styles as dark while the export runs, then restores light', () => {
    let during = ''
    withDarkTheme(() => {
      during = root().className
    })
    expect(during).toBe('dark')
    expect(root().className).toBe('light')
  })

  it('restores light even when the export throws', () => {
    expect(() =>
      withDarkTheme(() => {
        throw new Error('boom')
      }),
    ).toThrow('boom')
    expect(root().className).toBe('light')
  })

  it('leaves dark alone', () => {
    root().className = 'dark'
    expect(withDarkTheme(() => root().className)).toBe('dark')
    expect(root().className).toBe('dark')
  })

  it('turns an html2canvas clone dark', () => {
    const clone = document.implementation.createHTMLDocument('clone')
    clone.documentElement.className = 'light'
    asDarkClone(clone)
    expect(clone.documentElement.classList.contains('dark')).toBe(true)
    expect(clone.documentElement.classList.contains('light')).toBe(false)
  })
})
