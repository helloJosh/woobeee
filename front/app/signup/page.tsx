import { Suspense } from "react"
import SignupForm from "@/components/auth/signup-form"

// SignupForm 은 useSearchParams(`?next=`)를 읽는다. Suspense 경계가 없으면 정적 프리렌더
// 단계에서 next build 가 멈춘다.
export default function SignupPage() {
    return (
        <Suspense fallback={<SignupFallback />}>
            <SignupForm />
        </Suspense>
    )
}

function SignupFallback() {
    return (
        <div className="min-h-screen flex items-center justify-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
        </div>
    )
}
