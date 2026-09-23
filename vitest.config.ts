import { configDefaults, defineConfig } from 'vitest/config'

// Launcher-Tests. api/ (TRS API) und client-mod/ haben eigene Test-Setups.
export default defineConfig({
  test: {
    exclude: [...configDefaults.exclude, 'api/**', 'client-mod/**'],
  },
})
