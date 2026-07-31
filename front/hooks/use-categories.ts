"use client"

import { useState, useEffect } from "react"
import { categoryAPI } from "@/lib/api"
import type { Category } from "@/lib/types"
import { mockCategories} from "@/lib/mock-data";

export function useCategories() {
    const [categories, setCategories] = useState<Category[]>([])
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    const fetchCategories = async () => {
        try {
            setLoading(true)
            setError(null)

            const data = await categoryAPI.categories()
            //const data = mockCategories
            setCategories(data)
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : "카테고리를 불러오는데 실패했습니다."
            setError(errorMessage)
            console.error("Failed to fetch categories:", err)
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        fetchCategories()
    }, [])

    const refresh = async () => {
        await fetchCategories()
    }

    return {
        categories,
        loading,
        error,
        refresh,
    }
}