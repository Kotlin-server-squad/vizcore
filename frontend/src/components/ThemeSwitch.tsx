import { Button } from '@heroui/react'
import { FiMoon, FiSun } from 'react-icons/fi'
import { useTheme } from '@/hooks/use-theme'

/** ☾ / ☀ in the top bar: shows the theme you would switch to. */
export function ThemeSwitch() {
  const { theme, toggle } = useTheme()
  const next = theme === 'dark' ? 'light' : 'dark'
  return (
    <Button isIconOnly variant="light" aria-label={`Switch to ${next} theme`} onPress={toggle}>
      {next === 'light' ? <FiSun className="h-5 w-5" /> : <FiMoon className="h-5 w-5" />}
    </Button>
  )
}
