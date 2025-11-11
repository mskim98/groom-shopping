/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    async rewrites() {
        return [
            {
                source: '/api/v1/:path*',
                destination: 'http://localhost:8080/api/v1/:path*',
            },
        ];
    },
    images: {
        remotePatterns: [
            {
                protocol: 'https',
                hostname: '**',
            },
            {
                protocol: 'http',
                hostname: 'backend',
                port: '8080',
            },
            {
                protocol: 'http',
                hostname: 'localhost',
                port: '8080',
            },
        ],
    },
}

module.exports = nextConfig