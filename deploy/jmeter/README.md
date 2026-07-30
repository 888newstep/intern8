# JMeter Performance Test

## Prerequisites
- Apache JMeter 5.6+ installed
- Application running on localhost:8080

## How to Run

### CLI Mode (no GUI, for CI/CD)
```bash
jmeter -n -t performance-test.jmx -l results.jtl -e -o report/
```

### GUI Mode (for debugging)
```bash
jmeter -t performance-test.jmx
```

## Test Parameters
- **Threads**: 100 concurrent users
- **Ramp-up**: 10 seconds
- **Loops**: 5 iterations per user
- **Total requests**: ~500 (100 users * 5 loops)

## Test Scenarios
| Endpoint | Weight | Description |
|----------|--------|-------------|
| GET /api/dynamic/feed | 60% | Feed reading (main scenario) |
| POST /api/dynamic/like | 15% | Like interaction |
| POST /api/dynamic/publish | 10% | Content publishing |
| POST /api/comment/add | 10% | Comment interaction |
| POST /api/comment/list | 5% | Comment reading |

## Expected Results
- **QPS Target**: 2000+ (with proper caching)
- **P99 Latency**: < 100ms (for feed queries)
- **Error Rate**: < 0.1%

## Viewing Results
After running in CLI mode:
```bash
# Open report/index.html in browser
# Or use the results.jtl file with JMeter GUI
```