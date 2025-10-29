/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    experimental: {
        serverComponentsExternalPackages: [],
    },
    async rewrites() {
        return [
            {
                source: '/api/:path*',
                destination: 'http://backend:8080/api/:path*',
            },
        ];
    },
}

module.exports = nextConfig