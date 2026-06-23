import { useState } from 'react'
import { createFileRoute, useNavigate, useSearch } from '@tanstack/react-router'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button, Card, CardBody, Input } from '@heroui/react'
import { ErrorAlert } from '@/components/ErrorAlert'
import { apiClient, LoginAuthError } from '@/lib/api-client'
import { setToken } from '@/lib/auth-store'

// UI-SPEC copy (sentence case, no exclamation, no emoji).
const COPY = {
  heading: 'Sign in',
  subhead: 'This server requires authentication.',
  username: 'Username',
  password: 'Password',
  submit: 'Sign in',
  submitting: 'Signing in…',
  error401: 'Incorrect username or password.',
  errorNetwork: 'Could not reach the server. Check your connection and try again.',
} as const

interface LoginSearch {
  // Where to return after a successful sign-in (D-05 resume). Defaults to '/'.
  redirect?: string
}

const loginSchema = z.object({
  username: z.string().min(1, 'Username is required'),
  password: z.string().min(1, 'Password is required'),
})

type LoginData = z.infer<typeof loginSchema>

export const Route = createFileRoute('/login')({
  // `/login` is registered OUTSIDE the Layout nav chrome (UI-SPEC focal point).
  component: LoginPage,
  validateSearch: (search: Record<string, unknown>): LoginSearch => ({
    redirect: typeof search.redirect === 'string' ? search.redirect : undefined,
  }),
})

export function LoginPage() {
  const navigate = useNavigate()
  const { redirect } = useSearch({ strict: false }) as LoginSearch
  // `null` = no error; the two error copies are distinguished by the api-client
  // (401 → wrong-credentials; anything else → network/server).
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginData>({ resolver: zodResolver(loginSchema) })

  const onSubmit = async (data: LoginData) => {
    setFormError(null)
    try {
      const { token } = await apiClient.login(data.username, data.password)
      setToken(token)
      void navigate({ to: redirect ?? '/' })
    } catch (err) {
      // 401 = wrong credentials; any other failure (network/500) = server copy.
      // Credentials are never logged.
      setFormError(err instanceof LoginAuthError ? COPY.error401 : COPY.errorNetwork)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4 pt-16">
      <Card className="w-full max-w-sm">
        <CardBody className="gap-6 p-6">
          <div className="space-y-1 text-center">
            <h1 className="text-2xl font-semibold">{COPY.heading}</h1>
            <p className="text-sm text-default-500">{COPY.subhead}</p>
          </div>

          {formError && <ErrorAlert message={formError} title="Sign in failed" />}

          <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4">
            <Input
              label={COPY.username}
              placeholder={COPY.username}
              autoComplete="username"
              {...register('username')}
              isInvalid={Boolean(errors.username)}
              errorMessage={errors.username?.message}
            />
            <Input
              label={COPY.password}
              placeholder={COPY.password}
              type="password"
              autoComplete="current-password"
              {...register('password')}
              isInvalid={Boolean(errors.password)}
              errorMessage={errors.password?.message}
            />
            <Button type="submit" color="primary" isLoading={isSubmitting} className="w-full">
              {isSubmitting ? COPY.submitting : COPY.submit}
            </Button>
          </form>
        </CardBody>
      </Card>
    </div>
  )
}
