import js from '@eslint/js'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['.output/**', '.nuxt/**', '.nitro/**', 'node_modules/**', 'coverage/**', 'assets/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: {
      globals: {
        // Nitro-Auto-Imports (nur in server/plugins und server/error-handler.ts benutzt)
        defineNitroPlugin: 'readonly',
        defineNitroErrorHandler: 'readonly',
        useStorage: 'readonly',
        defineNuxtConfig: 'readonly',
        process: 'readonly',
        console: 'readonly',
        Buffer: 'readonly',
        URL: 'readonly',
        fetch: 'readonly',
        Response: 'readonly',
        AbortController: 'readonly',
        AbortSignal: 'readonly',
        TextDecoder: 'readonly',
        performance: 'readonly',
        setTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
        clearTimeout: 'readonly',
      },
    },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', destructuredArrayIgnorePattern: '^_' }],
      'no-restricted-syntax': [
        'error',
        { selector: "CallExpression[callee.property.name='exec'][arguments.0.type='TemplateLiteral'][arguments.0.expressions.length>0]", message: 'No interpolated SQL – use prepared statements.' },
      ],
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      'no-eval': 'error',
      'no-new-func': 'error',
    },
  },
)
