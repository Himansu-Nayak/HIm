import { NextRequest, NextResponse } from 'next/server';

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const apiUrl = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
    // Append /api if not present
    const baseUrl = apiUrl.endsWith('/api') ? apiUrl : `${apiUrl}/api`;
    
    const authHeader = req.headers.get('Authorization');
    
    const response = await fetch(`${baseUrl}/aibh/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(authHeader ? { Authorization: authHeader } : {}),
      },
      body: JSON.stringify(body),
    });
    
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    console.error('Chat proxy error:', error);
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    return NextResponse.json({ error: 'Backend unreachable: ' + errorMessage }, { status: 503 });
  }
}
