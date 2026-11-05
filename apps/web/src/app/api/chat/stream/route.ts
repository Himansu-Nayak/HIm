import { NextRequest } from 'next/server';

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { chatId, prompt, model } = body;

    // Forward the request to the Java Spring Boot Backend
    // The Java backend handles security, RAG, and multi-model routing.
    const apiUrl = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
    const JAVA_BACKEND_URL = apiUrl.endsWith('/api') ? apiUrl : `${apiUrl}/api`;
    const STREAM_ENDPOINT = `${JAVA_BACKEND_URL}/aibh/chat/stream`;
    
    console.log(`[Streaming Gateway] Forwarding to: ${STREAM_ENDPOINT}`);

    // 1. Orchestrate Chat via Java Backend (which handles its own memory/RAG)
    const backendUrl = `${STREAM_ENDPOINT}?message=${encodeURIComponent(prompt)}&sessionId=${chatId}${model ? `&model=${encodeURIComponent(model)}` : ''}`;
    console.log(`[Streaming Gateway] Full URL: ${backendUrl}`);

    const response = await fetch(backendUrl, {
      method: 'GET',
      headers: {
        'Accept': 'text/event-stream',
      }
    });

    console.log(`[Streaming Gateway] Backend status: ${response.status}`);

    if (!response.ok) {
      throw new Error(`Java Backend responded with ${response.status}`);
    }

    const encoder = new TextEncoder();
    const decoder = new TextDecoder();
    
    const stream = new ReadableStream({
      async start(controller) {
        const reader = response.body?.getReader();
        if (!reader) {
          controller.close();
          return;
        }

        let buffer = '';
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            
            // Keep the last incomplete line in the buffer
            buffer = lines.pop() || '';

            for (const line of lines) {
              if (line.startsWith('data:')) {
                // Slice off 'data:' but DO NOT trim(), as we need to preserve spaces and newlines
                let data = line.slice(5);
                if (data.startsWith(' ')) data = data.slice(1); // SSE standard: remove one leading space if present
                
                if (data === '[DONE]') continue; // Spring AI sometimes sends [DONE]
                
                controller.enqueue(encoder.encode(data));
              }
            }
          }
          
          // Process any remaining buffer
          if (buffer.startsWith('data:')) {
             let data = buffer.slice(5);
             if (data.startsWith(' ')) data = data.slice(1);
             if (data !== '[DONE]') {
               controller.enqueue(encoder.encode(data));
             }
          }
          
        } finally {
          controller.close();
        }
      },
    });

    return new Response(stream, {
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
    });
  } catch (error) {
    console.error('Streaming gateway error:', error);
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    return new Response(JSON.stringify({ error: `Streaming gateway error: ${errorMessage}` }), { 
      status: 500,
      headers: { 'Content-Type': 'application/json' }
    });
  }
}
