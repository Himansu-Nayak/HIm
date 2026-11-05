#!/bin/bash
echo "🚀 Starting HIm Development Environment..."

# Function to kill all background processes on exit
cleanup() {
    echo ""
    echo "Stopping all services..."
    kill $(jobs -p)
    exit
}
trap cleanup SIGINT

# Start Java backend
echo "[1/5] Starting Java Backend..."
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local &
BACKEND_PID=$!

# Wait for backend
sleep 5

# Start Go memory service
echo "[2/5] Starting Go Memory Service..."
cd ../services/memory-service && go run main.go &

# Start Python agent engine
echo "[3/5] Starting Python Agent Engine..."
cd ../agent-engine && python3 main.py &

# Start Next.js frontend
echo "[4/5] Starting Next.js Frontend..."
cd ../../apps/web && npm run dev &

# Start legacy React frontend
echo "[5/5] Starting Legacy React Frontend..."
cd ../../frontend && npm run dev &

echo ""
echo "✅ ALL SERVICES INITIATED!"
echo "--------------------------------------------------"
echo "Legacy Frontend:  http://localhost:3000"
echo "New Frontend UI:  http://localhost:3001"
echo "Core API:         http://localhost:8080/api"
echo "Agent Engine:     http://localhost:8001"
echo "Memory Svc:       http://localhost:8002"
echo "--------------------------------------------------"
echo "Press Ctrl+C to stop all services"

wait
